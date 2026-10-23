"""One-worker authenticated bridge. Only synthesized WAV can enter the PSTN stream."""
import asyncio
import base64
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
import json
import os
import re
import secrets
import time
from urllib.parse import urlsplit
import uuid
from xml.sax.saxutils import escape

import httpx
from fastapi import FastAPI, HTTPException, Request, WebSocket, WebSocketDisconnect
from pydantic import BaseModel, Field
from twilio.request_validator import RequestValidator

from .audio import MAX_WAV, inbound_pcm, wav_to_mulaw


@dataclass
class Config:
    mode: str = 'mock'
    token: str = ''
    public_url: str = ''
    account: str = ''
    auth_token: str = ''
    from_number: str = ''
    prefixes: tuple = ('+880', '+60')
    max_seconds: int = 1800

    @classmethod
    def env(cls):
        return cls(os.getenv('GATEWAY_MODE', 'mock'), os.getenv('GATEWAY_TOKEN', ''),
                   os.getenv('PUBLIC_BASE_URL', '').rstrip('/'), os.getenv('TWILIO_ACCOUNT_SID', ''),
                   os.getenv('TWILIO_AUTH_TOKEN', ''), os.getenv('TWILIO_FROM_NUMBER', ''),
                   tuple(p.strip() for p in os.getenv('ALLOWED_PREFIXES', '+880,+60').split(',')),
                   int(os.getenv('MAX_CALL_SECONDS', '1800')))

    def validate(self):
        if self.mode not in ('mock', 'twilio') or not re.fullmatch(r'[A-Za-z0-9_-]{32,256}', self.token) or self.token.startswith('REPLACE_'):
            raise RuntimeError('Set GATEWAY_MODE and a random GATEWAY_TOKEN (32+ characters)')
        if not 60 <= self.max_seconds <= 7200:
            raise RuntimeError('MAX_CALL_SECONDS must be 60..7200')
        if not self.prefixes or any(not re.fullmatch(r'\+[1-9][0-9]{0,3}', p) for p in self.prefixes):
            raise RuntimeError('Invalid ALLOWED_PREFIXES')
        if self.mode == 'twilio':
            url = urlsplit(self.public_url)
            if url.scheme != 'https' or not url.hostname or url.path or url.query or url.fragment or url.username:
                raise RuntimeError('PUBLIC_BASE_URL must be an HTTPS origin')
            if not re.fullmatch(r'AC[0-9a-fA-F]{32}', self.account) or not self.auth_token:
                raise RuntimeError('Set Twilio account credentials')
            if not re.fullmatch(r'\+[1-9][0-9]{7,14}', self.from_number):
                raise RuntimeError('Set TWILIO_FROM_NUMBER in E.164 format')


@dataclass
class Session:
    id: str
    number: str
    secret: str = field(default_factory=lambda: secrets.token_urlsafe(32))
    created: float = field(default_factory=time.monotonic)
    state: str = 'reserved'
    call_sid: str = ''
    stream_sid: str = ''
    app_ws: WebSocket | None = None
    phone_ws: WebSocket | None = None
    pending: str | None = None
    pending_at: float = 0
    seen: set = field(default_factory=set)
    ending: bool = False
    dial_attempted: bool = False
    dial_at: float = 0
    lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    app_lock: asyncio.Lock = field(default_factory=asyncio.Lock)


class CreateCall(BaseModel):
    number: str = Field(min_length=9, max_length=16)


class TwilioProvider:
    def __init__(self, config):
        self.config = config

    async def dial(self, session):
        c = self.config
        stream = c.public_url.replace('https://', 'wss://', 1) + '/twilio/media/' + session.id
        twiml = ('<Response><Connect><Stream url="' + escape(stream, {'"': '&quot;'}) + '">'
                 '<Parameter name="secret" value="' + session.secret + '"/>'
                 '</Stream></Connect><Hangup/></Response>')
        async with httpx.AsyncClient(timeout=15) as client:
            response = await client.post(f'https://api.twilio.com/2010-04-01/Accounts/{c.account}/Calls.json',
                auth=(c.account, c.auth_token), data={'To': session.number, 'From': c.from_number,
                'Twiml': twiml, 'Timeout': '30', 'TimeLimit': str(c.max_seconds),
                'StatusCallback': c.public_url + '/twilio/status/' + session.id,
                'StatusCallbackEvent': ['initiated', 'ringing', 'answered', 'completed']})
            response.raise_for_status()
            sid = response.json()['sid']
            if not re.fullmatch(r'CA[0-9a-fA-F]{32}', sid):
                raise ValueError('provider_response')
            return sid

    async def hangup(self, session):
        if not session.call_sid:
            return
        c = self.config
        async with httpx.AsyncClient(timeout=10) as client:
            response = await client.post(f'https://api.twilio.com/2010-04-01/Accounts/{c.account}/Calls/{session.call_sid}.json',
                auth=(c.account, c.auth_token), data={'Status': 'completed'})
            response.raise_for_status()


class Bridge:
    def __init__(self, config, provider=None):
        self.config = config
        self.provider = provider or TwilioProvider(config)
        self.sessions = {}

    def session(self, call_id):
        if call_id not in self.sessions:
            raise HTTPException(404, 'call_not_found')
        return self.sessions[call_id]

    async def event(self, session, event, **data):
        if session.app_ws:
            try:
                async with session.app_lock:
                    await asyncio.wait_for(session.app_ws.send_json({'event': event, **data}), 5)
            except Exception:
                # App is no longer reachable; janitor will terminate the call.
                session.ending = True

    async def end(self, session, reason='ended', terminal=False):
        async with session.lock:
            if session.state == 'ended':
                return True
            session.ending = True
            if not terminal and self.config.mode == 'twilio':
                # A create-call timeout can leave the Call SID unknown. Signed callbacks
                # can still recover it; don't claim hangup until the provider time limit expires.
                if session.dial_attempted and not session.call_sid and time.monotonic() - session.created < self.config.max_seconds + 45:
                    session.state = 'ending'
                    await self.event(session, 'status', state='ending', reason='dial_uncertain')
                    return False
                try:
                    await self.provider.hangup(session)
                except Exception:
                    session.state = 'ending'
                    await self.event(session, 'status', state='ending', reason='hangup_retry')
                    return False
            session.state = 'ended'
            session.pending = None
            await self.event(session, 'ended', reason=reason)
            for ws in (session.phone_ws, session.app_ws):
                if ws:
                    try:
                        await asyncio.wait_for(ws.close(code=1000), 5)
                    except Exception:
                        pass
            return True

    async def dial(self, session):
        rejected = False
        async with session.lock:
            if session.state != 'reserved' or session.ending:
                return  # Never repeat an outbound call on reconnect.
            session.state = 'dialing'
            session.dial_attempted = True
            session.dial_at = time.monotonic()
            if self.config.mode == 'mock':
                session.call_sid = 'CA' + '0' * 32
                session.state = 'connected'
                await self.event(session, 'status', state='connected', mode='mock')
                return
            await self.event(session, 'status', state='dialing', mode='twilio')
            try:
                session.call_sid = await self.provider.dial(session)
            except httpx.HTTPStatusError as error:
                if 400 <= error.response.status_code < 500 and error.response.status_code != 408:
                    rejected = True
                    session.state = 'rejected'
                    session.ending = True
                else:
                    session.state = 'uncertain'
                    session.ending = True
                    await self.event(session, 'error', code='dial_uncertain_check_provider')
            except Exception:
                # No retry: an ambiguous HTTP timeout may already have created a paid call.
                session.state = 'uncertain'
                session.ending = True
                await self.event(session, 'error', code='dial_uncertain_check_provider')
        if rejected:
            await self.end(session, 'failed', terminal=True)

    async def sweep(self):
        for session in list(self.sessions.values()):
            age = time.monotonic() - session.created
            if session.state == 'ended':
                if age > self.config.max_seconds + 120:
                    self.sessions.pop(session.id, None)
                continue
            if session.ending or age > self.config.max_seconds or (session.state == 'reserved' and age > 30):
                await self.end(session, 'timeout')
            elif session.dial_at and session.state != 'connected' and time.monotonic() - session.dial_at > 60:
                # Failed media attachment must not leave an answered paid call silent for 30 minutes.
                await self.end(session, 'connection_timeout')
            elif session.pending and time.monotonic() - session.pending_at > 90:
                # Delivery is uncertain; do not allow resending over an uncleared phone buffer.
                await self.event(session, 'error', code='playback_timeout_call_ended')
                await self.end(session, 'playback_timeout')


def create_app(config=None, provider=None):
    config = config or Config.env()
    bridge = Bridge(config, provider)

    @asynccontextmanager
    async def lifespan(app):
        config.validate()
        async def janitor():
            while True:
                await asyncio.sleep(3)
                await bridge.sweep()
        task = asyncio.create_task(janitor())
        yield
        task.cancel()
        for session in list(bridge.sessions.values()):
            await bridge.end(session, 'server_shutdown')

    app = FastAPI(lifespan=lifespan)
    app.state.bridge = bridge

    def auth(headers):
        expected = 'Bearer ' + config.token
        if not config.token or not secrets.compare_digest(headers.get('authorization', '').encode('utf-8'), expected.encode('utf-8')):
            raise HTTPException(401, 'unauthorized')

    @app.post('/v1/calls')
    async def create(body: CreateCall, request: Request):
        auth(request.headers)
        if not re.fullmatch(r'\+[1-9][0-9]{7,14}', body.number):
            raise HTTPException(400, 'invalid_number')
        if not any(body.number.startswith(p) for p in config.prefixes):
            raise HTTPException(403, 'destination_disabled')
        if any(s.state != 'ended' for s in bridge.sessions.values()):
            raise HTTPException(409, 'call_already_active')
        if len(bridge.sessions) >= 100:
            raise HTTPException(429, 'session_limit')
        session = Session(str(uuid.uuid4()), body.number)
        bridge.sessions[session.id] = session
        # Reservation is free. Actual dialing happens only after the app WebSocket attaches.
        return {'callId': session.id, 'mode': config.mode}

    @app.delete('/v1/calls/{call_id}')
    async def hangup(call_id: str, request: Request):
        auth(request.headers)
        ended = await bridge.end(bridge.session(call_id))
        if not ended:
            raise HTTPException(503, 'hangup_pending')
        return {'state': 'ended'}

    @app.get('/v1/calls/active')
    async def current_call(request: Request):
        auth(request.headers)
        session = next((s for s in bridge.sessions.values() if s.state != 'ended'), None)
        return {'callId': session.id if session else None, 'state': session.state if session else 'idle', 'mode': config.mode}

    @app.post('/v1/calls/{call_id}/utterances/{utterance_id}')
    async def upload(call_id: str, utterance_id: str, request: Request):
        auth(request.headers)
        session = bridge.session(call_id)
        try:
            if str(uuid.UUID(utterance_id)) != utterance_id:
                raise ValueError()
        except ValueError:
            raise HTTPException(400, 'invalid_utterance')
        if request.headers.get('content-type', '').split(';')[0] != 'audio/wav':
            raise HTTPException(415, 'wav_required')
        body = bytearray()
        async for chunk in request.stream():
            body.extend(chunk)
            if len(body) > MAX_WAV:
                raise HTTPException(413, 'audio_size')
        try:
            audio = await asyncio.to_thread(wav_to_mulaw, bytes(body))
        except ValueError as exc:
            raise HTTPException(400, str(exc))
        async with session.lock:
            if session.state != 'connected' or session.ending:
                raise HTTPException(409, 'call_not_connected')
            if session.pending or utterance_id in session.seen or len(session.seen) >= 1000:
                raise HTTPException(409, 'utterance_busy_or_replayed')
            if config.mode == 'twilio' and not session.phone_ws:
                raise HTTPException(409, 'stream_not_ready')
            session.seen.add(utterance_id)
            session.pending, session.pending_at = utterance_id, time.monotonic()
            if config.mode == 'mock':
                # Explicitly simulated acknowledgement; never presents a mock call as a real call.
                await bridge.event(session, 'played', utteranceId=utterance_id, simulated=True)
                session.pending = None
            else:
                try:
                    for offset in range(0, len(audio), 16000):
                        await asyncio.wait_for(session.phone_ws.send_json({'event': 'media',
                            'streamSid': session.stream_sid,
                            'media': {'payload': base64.b64encode(audio[offset:offset+16000]).decode('ascii')}}), 5)
                    await asyncio.wait_for(session.phone_ws.send_json({'event': 'mark', 'streamSid': session.stream_sid,
                                                     'mark': {'name': utterance_id}}), 5)
                except Exception:
                    session.ending = True
                    raise HTTPException(502, 'delivery_uncertain_call_ending')
        return {'accepted': True, 'simulated': config.mode == 'mock'}

    @app.post('/v1/calls/{call_id}/mock/inbound')
    async def mock_inbound(call_id: str, request: Request):
        auth(request.headers)
        if config.mode != 'mock':
            raise HTTPException(404)
        session = bridge.session(call_id)
        if session.state != 'connected' or not session.app_ws or session.ending:
            raise HTTPException(409, 'call_not_connected')
        raw = bytearray()
        async for chunk in request.stream():
            raw.extend(chunk)
            if len(raw) > MAX_WAV:
                raise HTTPException(413, 'audio_size')
        try:
            audio = await asyncio.to_thread(wav_to_mulaw, bytes(raw))
        except ValueError as exc:
            raise HTTPException(400, str(exc))
        for offset in range(0, len(audio), 160):
            if not session.app_ws or session.ending:
                raise HTTPException(409, 'call_ended')
            async with session.app_lock:
                await asyncio.wait_for(session.app_ws.send_bytes(inbound_pcm(audio[offset:offset+160])), 5)
            await asyncio.sleep(.02)
        return {'simulated': True}

    @app.websocket('/v1/calls/{call_id}/events')
    async def app_events(ws: WebSocket, call_id: str):
        try:
            auth(ws.headers)
            session = bridge.session(call_id)
            if session.app_ws or session.state != 'reserved':
                raise HTTPException(409, 'duplicate_attachment')
        except HTTPException:
            await ws.close(code=1008)
            return
        session.app_ws = ws
        try:
            await ws.accept()
            await bridge.event(session, 'attached', mode=config.mode)
            while True:
                # App may send only control messages, never audio.
                frame = await ws.receive()
                if frame['type'] == 'websocket.disconnect':
                    break
                raw = frame.get('text')
                if not raw or len(raw) > 1024:
                    break
                data = json.loads(raw)
                if not isinstance(data, dict):
                    break
                if data.get('event') == 'dial':
                    await bridge.dial(session)
                elif data.get('event') == 'hangup':
                    break
                else:
                    break
        except (WebSocketDisconnect, ValueError, RuntimeError):
            pass
        finally:
            await bridge.end(session, 'app_disconnected')
            session.app_ws = None

    async def phone_messages(ws, session):
        async for raw in ws.iter_text():
            if len(raw) > 65536:
                raise ValueError('oversized_frame')
            data = json.loads(raw)
            if data.get('streamSid') != session.stream_sid:
                raise ValueError('wrong_stream')
            event = data.get('event')
            if event == 'media':
                media = data.get('media', {})
                if media.get('track') != 'inbound':
                    continue
                payload = base64.b64decode(media['payload'], validate=True)
                if len(payload) > 8000:
                    raise ValueError('audio_frame_size')
                if session.app_ws:
                    async with session.app_lock:
                        await asyncio.wait_for(session.app_ws.send_bytes(inbound_pcm(payload)), 5)
            elif event == 'mark':
                async with session.lock:
                    name = data.get('mark', {}).get('name')
                    if name and name == session.pending:
                        session.pending = None
                        await bridge.event(session, 'played', utteranceId=name, simulated=False)
            elif event == 'stop':
                return

    @app.websocket('/twilio/media/{call_id}')
    async def phone_stream(ws: WebSocket, call_id: str):
        # Validate the externally configured URL, never the reverse proxy's Host header.
        path = '/twilio/media/' + call_id
        validator = RequestValidator(config.auth_token)
        external = config.public_url.replace('https://', 'wss://', 1) + path
        signature = ws.headers.get('x-twilio-signature', '')
        # Twilio documents a trailing-slash variant for WSS handshake signatures.
        if config.mode != 'twilio' or not any(validator.validate(url, {}, signature) for url in (external, external + '/')):
            await ws.close(code=1008)
            return
        try:
            session = bridge.session(call_id)
            if session.phone_ws or session.state not in ('dialing', 'ringing', 'answered', 'queued', 'uncertain') or session.ending:
                raise ValueError()
        except (HTTPException, ValueError):
            await ws.close(code=1008)
            return
        session.phone_ws = ws
        try:
            await ws.accept()
            first = await asyncio.wait_for(ws.receive_text(), 10)
            if len(first) > 8192:
                raise ValueError()
            message = json.loads(first)
            if message.get('event') == 'connected':
                raw_start = await asyncio.wait_for(ws.receive_text(), 10)
                if len(raw_start) > 8192:
                    raise ValueError('oversized_start')
                message = json.loads(raw_start)
            start = message.get('start', {})
            if (message.get('event') != 'start' or start.get('accountSid') != config.account
                or start.get('tracks') != ['inbound']
                or not secrets.compare_digest(start.get('customParameters', {}).get('secret', ''), session.secret)
                or start.get('mediaFormat') != {'encoding': 'audio/x-mulaw', 'sampleRate': 8000, 'channels': 1}
                or not re.fullmatch(r'MZ[0-9a-fA-F]{32}', message.get('streamSid', ''))
                or message.get('streamSid') != start.get('streamSid')
                or not re.fullmatch(r'CA[0-9a-fA-F]{32}', start.get('callSid', ''))
                or (session.call_sid and start['callSid'] != session.call_sid)):
                raise ValueError('invalid_start')
            session.call_sid = start['callSid']
            session.phone_ws, session.stream_sid = ws, message['streamSid']
            session.state = 'connected'
            await bridge.event(session, 'status', state='connected', mode='twilio')
            await phone_messages(ws, session)
        except Exception:
            await bridge.event(session, 'error', code='phone_stream_failed')
        finally:
            await bridge.end(session, 'phone_disconnected')
            session.phone_ws = None

    @app.post('/twilio/status/{call_id}')
    async def callback(call_id: str, request: Request):
        if config.mode != 'twilio':
            raise HTTPException(404)
        # Bound even chunked requests before form parsing.
        raw = bytearray()
        async for chunk in request.stream():
            raw.extend(chunk)
            if len(raw) > 16384:
                raise HTTPException(413)
        from starlette.datastructures import FormData
        from urllib.parse import parse_qsl
        if request.headers.get('content-type', '').split(';')[0] != 'application/x-www-form-urlencoded':
            raise HTTPException(415)
        try:
            pairs = parse_qsl(raw.decode('utf-8'), max_num_fields=50, keep_blank_values=True)
        except (ValueError, UnicodeError):
            raise HTTPException(400)
        form = FormData(pairs)
        if not RequestValidator(config.auth_token).validate(config.public_url + '/twilio/status/' + call_id,
                form, request.headers.get('x-twilio-signature', '')):
            raise HTTPException(403, 'signature')
        session = bridge.session(call_id)
        sid = form.get('CallSid', '')
        if form.get('AccountSid') != config.account or not re.fullmatch(r'CA[0-9a-fA-F]{32}', sid):
            raise HTTPException(403, 'wrong_account')
        if session.call_sid and session.call_sid != sid:
            raise HTTPException(403, 'wrong_call')
        if session.state == 'reserved':
            raise HTTPException(409, 'not_dialing')
        session.call_sid = sid
        state = form.get('CallStatus')
        if state in ('completed', 'busy', 'failed', 'no-answer', 'canceled'):
            await bridge.end(session, state, terminal=True)
        elif session.ending:
            # A signed callback recovered an ambiguous create-call SID; retry hangup now.
            await bridge.end(session, 'hangup_requested')
        elif session.state != 'connected' and session.state != 'ended' and not session.ending:
            if state in ('ringing', 'in-progress', 'queued'):
                session.state = 'answered' if state == 'in-progress' else state
                await bridge.event(session, 'status', state=session.state, mode='twilio')
        return {'ok': True}

    return app


app = create_app()
