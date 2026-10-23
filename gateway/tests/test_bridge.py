import asyncio
import base64
import io
import struct
import time
import uuid
import wave

import httpx
import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect
from twilio.request_validator import RequestValidator

from gateway.audio import decode_sample, encode_sample, inbound_pcm, wav_to_mulaw
from gateway.server import Config, Session, create_app, TwilioProvider

TOKEN = 'test-only-' + 'x' * 32
AUTH = {'Authorization': 'Bearer ' + TOKEN}
SID = 'CA' + '1' * 32
STREAM = 'MZ' + '2' * 32


def wav(samples=(0, 1000, -1000, 20000), rate=8000, channels=1):
    output = io.BytesIO()
    with wave.open(output, 'wb') as writer:
        writer.setnchannels(channels)
        writer.setsampwidth(2)
        writer.setframerate(rate)
        writer.writeframes(struct.pack('<' + 'h' * len(samples), *samples))
    return output.getvalue()


class Provider:
    def __init__(self):
        self.calls, self.hangups, self.fail_hangup = 0, 0, False
    async def dial(self, session):
        self.calls += 1
        return SID
    async def hangup(self, session):
        self.hangups += 1
        if self.fail_hangup:
            raise RuntimeError('do not disclose provider credentials')


def config(mode='mock'):
    return Config(mode=mode, token=TOKEN, public_url='https://bridge.example.com',
                  account='AC' + 'a' * 32, auth_token='server-secret', from_number='+12025550123')


def reserve(client):
    response = client.post('/v1/calls', headers=AUTH, json={'number': '+60123456789'})
    assert response.status_code == 200
    return response.json()['callId']


def attach(client, call_id):
    return client.websocket_connect('/v1/calls/' + call_id + '/events', headers=AUTH)


def dial(ws):
    assert ws.receive_json()['event'] == 'attached'
    ws.send_json({'event': 'dial'})
    return ws.receive_json()


def phone(client, app, call_id, slash=False):
    path = '/twilio/media/' + call_id
    url = 'wss://bridge.example.com' + path + ('/' if slash else '')
    signature = RequestValidator('server-secret').compute_signature(url, {})
    return client.websocket_connect(path, headers={'X-Twilio-Signature': signature})


def start(ws, app, call_id, **changes):
    s = app.state.bridge.sessions[call_id]
    message = {'event': 'start', 'streamSid': STREAM, 'start': {'accountSid': app.state.bridge.config.account,
        'callSid': SID, 'streamSid': STREAM, 'tracks': ['inbound'],
        'mediaFormat': {'encoding': 'audio/x-mulaw', 'sampleRate': 8000, 'channels': 1},
        'customParameters': {'secret': s.secret}}}
    message['start'].update(changes)
    ws.send_json({'event': 'connected', 'protocol': 'Call', 'version': '1.0.0'})
    ws.send_json(message)


def upload(client, call_id, utterance=None, audio=None):
    return client.post(f'/v1/calls/{call_id}/utterances/{utterance or uuid.uuid4()}',
                       headers={**AUTH, 'Content-Type': 'audio/wav'}, content=audio or wav())


def test_g711_known_vectors_and_signed_pcm():
    assert [encode_sample(v) for v in (0, 1000, -1000, 32124, -32124)] == [255, 206, 78, 128, 0]
    assert [decode_sample(v) for v in (255, 127, 206, 78, 128, 0)] == [0, 0, 988, -988, 32124, -32124]
    assert struct.unpack('<hhhh', inbound_pcm(bytes([206, 78]))) == (988, 988, -988, -988)


def test_wav_resample_stereo_and_limits():
    assert wav_to_mulaw(wav((1000, -1000) * 4, rate=16000)) == bytes([255]*4)
    assert wav_to_mulaw(wav((1000, -1000) * 4, channels=2)) == bytes([255]*4)
    assert len(wav_to_mulaw(wav((1000,)*441, rate=44100))) == 80
    for data in [b'not a wav', wav(rate=96000), wav()[:-2]]:
        with pytest.raises(ValueError):
            wav_to_mulaw(data)


def test_config_fails_closed():
    for c in [Config(), Config(token='short'), Config(token=TOKEN, mode='other'),
              Config(token=TOKEN, mode='twilio'), Config(token=TOKEN, prefixes=('',))]:
        with pytest.raises(RuntimeError):
            c.validate()


def test_auth_destination_and_reservation_does_not_dial():
    provider = Provider()
    with TestClient(create_app(config('twilio'), provider)) as client:
        assert client.post('/v1/calls', json={'number': '+60123456789'}).status_code == 401
        assert client.post('/v1/calls', headers=AUTH, json={'number': '+12025550123'}).status_code == 403
        assert client.post('/v1/calls', headers=AUTH, json={'number': '60123456789'}).status_code == 400
        call_id = reserve(client)
        assert provider.calls == 0
        assert client.post('/v1/calls', headers=AUTH, json={'number': '+8801712345678'}).status_code == 409
        assert client.delete('/v1/calls/' + call_id, headers=AUTH).status_code == 200


def test_mock_synthesized_audio_ack_replay_and_disconnect():
    app = create_app(config())
    with TestClient(app) as client:
        call_id = reserve(client)
        with attach(client, call_id) as ws:
            assert dial(ws)['mode'] == 'mock'
            u = str(uuid.uuid4())
            assert upload(client, call_id, u).json()['simulated'] is True
            event = ws.receive_json()
            assert event == {'event': 'played', 'utteranceId': u, 'simulated': True}
            assert upload(client, call_id, u).status_code == 409
            assert upload(client, call_id, audio=b'bad').status_code == 400
        assert app.state.bridge.sessions[call_id].state == 'ended'


def test_mock_remote_audio_is_streamed_as_pcm():
    with TestClient(create_app(config())) as client:
        call_id = reserve(client)
        with attach(client, call_id) as ws:
            dial(ws)
            response = client.post(f'/v1/calls/{call_id}/mock/inbound', headers=AUTH, content=wav())
            assert response.json()['simulated']
            assert ws.receive_bytes() == inbound_pcm(wav_to_mulaw(wav()))


def test_microphone_binary_upload_is_rejected_and_call_ends():
    app = create_app(config())
    with TestClient(app) as client:
        call_id = reserve(client)
        with attach(client, call_id) as ws:
            dial(ws)
            ws.send_bytes(b'raw-microphone-audio')
            assert ws.receive_json()['event'] == 'ended'
        assert app.state.bridge.sessions[call_id].state == 'ended'


def test_twilio_bidirectional_audio_and_only_mark_confirms_playback():
    provider = Provider()
    app = create_app(config('twilio'), provider)
    with TestClient(app) as client:
        call_id = reserve(client)
        with attach(client, call_id) as android:
            assert dial(android)['state'] == 'dialing'
            with phone(client, app, call_id, slash=True) as remote:
                start(remote, app, call_id)
                assert android.receive_json()['state'] == 'connected'
                remote.send_json({'event': 'media', 'streamSid': STREAM,
                                 'media': {'track': 'inbound', 'payload': base64.b64encode(bytes([206, 78])).decode()}})
                assert android.receive_bytes() == inbound_pcm(bytes([206, 78]))
                u = str(uuid.uuid4())
                assert upload(client, call_id, u).status_code == 200
                media = remote.receive_json()
                assert media['event'] == 'media'
                assert base64.b64decode(media['media']['payload']) == wav_to_mulaw(wav())
                mark = remote.receive_json()
                assert mark['mark']['name'] == u
                assert app.state.bridge.sessions[call_id].pending == u
                assert upload(client, call_id).status_code == 409
                remote.send_json({'event': 'mark', 'streamSid': STREAM, 'mark': {'name': u}})
                assert android.receive_json() == {'event': 'played', 'utteranceId': u, 'simulated': False}
            assert android.receive_json()['event'] == 'ended'
        assert provider.calls == 1
        assert provider.hangups >= 1


def test_invalid_signature_and_start_token_rejected():
    provider = Provider()
    app = create_app(config('twilio'), provider)
    with TestClient(app) as client:
        call_id = reserve(client)
        with pytest.raises(WebSocketDisconnect):
            with client.websocket_connect('/twilio/media/' + call_id):
                pass
        with attach(client, call_id) as android:
            dial(android)
            with phone(client, app, call_id) as remote:
                start(remote, app, call_id, customParameters={'secret': 'wrong'})
                assert android.receive_json()['event'] == 'error'
                assert android.receive_json()['event'] == 'ended'
        assert provider.hangups >= 1


def test_callback_validates_signature_and_call_and_ends():
    provider = Provider()
    app = create_app(config('twilio'), provider)
    with TestClient(app) as client:
        call_id = reserve(client)
        with attach(client, call_id) as android:
            dial(android)
            path = '/twilio/status/' + call_id
            params = {'AccountSid': config().account, 'CallSid': SID, 'CallStatus': 'busy'}
            assert client.post(path, data=params).status_code == 403
            signature = RequestValidator('server-secret').compute_signature('https://bridge.example.com' + path, params)
            assert client.post(path, data=params, headers={'X-Twilio-Signature': signature}).status_code == 200
            assert android.receive_json() == {'event': 'ended', 'reason': 'busy'}


def test_failed_hangup_is_retained_and_retried():
    provider = Provider()
    provider.fail_hangup = True
    app = create_app(config('twilio'), provider)
    with TestClient(app) as client:
        call_id = reserve(client)
        with attach(client, call_id) as ws:
            dial(ws)
            assert client.delete('/v1/calls/' + call_id, headers=AUTH).status_code == 503
            assert ws.receive_json()['state'] == 'ending'
            assert app.state.bridge.sessions[call_id].state == 'ending'
            provider.fail_hangup = False
            assert client.delete('/v1/calls/' + call_id, headers=AUTH).status_code == 200
            assert ws.receive_json()['event'] == 'ended'
        assert provider.hangups >= 2


def test_reservation_and_playback_timeouts_clean_up():
    app = create_app(config())
    bridge = app.state.bridge
    reserved = Session(str(uuid.uuid4()), '+60123456789', created=time.monotonic()-31)
    bridge.sessions[reserved.id] = reserved
    asyncio.run(bridge.sweep())
    assert reserved.state == 'ended'
    playing = Session(str(uuid.uuid4()), '+60123456789', state='connected', pending='test', pending_at=time.monotonic()-91)
    bridge.sessions[playing.id] = playing
    asyncio.run(bridge.sweep())
    assert playing.state == 'ended'


def test_twilio_rest_uses_only_connect_stream_and_time_limit(monkeypatch):
    import gateway.server as module
    recorded = []
    async def handle(request):
        recorded.append(request)
        return httpx.Response(201, json={'sid': SID})
    original = httpx.AsyncClient
    monkeypatch.setattr(module.httpx, 'AsyncClient', lambda **kw: original(transport=httpx.MockTransport(handle), **kw))
    s = Session(str(uuid.uuid4()), '+8801712345678')
    provider = TwilioProvider(config('twilio'))
    assert asyncio.run(provider.dial(s)) == SID
    from urllib.parse import parse_qs
    params = parse_qs(recorded[0].content.decode())
    assert '<Connect><Stream ' in params['Twiml'][0] and '<Say>' not in params['Twiml'][0]
    assert s.secret in params['Twiml'][0]
    assert params['TimeLimit'] == ['1800']
    assert params['To'] == ['+8801712345678']
