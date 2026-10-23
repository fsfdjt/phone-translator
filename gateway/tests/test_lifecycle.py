import asyncio
import time
import uuid
import httpx
from fastapi.testclient import TestClient
from twilio.request_validator import RequestValidator

from gateway.server import Session, create_app
from gateway.tests.test_bridge import AUTH, SID, TOKEN, Provider, attach, config, dial, reserve


def test_ambiguous_creation_retains_session_until_signed_callback_recovers_sid():
    class Ambiguous(Provider):
        async def dial(self, session):
            self.calls += 1
            raise httpx.ReadTimeout('private request detail')
    provider = Ambiguous()
    app = create_app(config('twilio'), provider)
    with TestClient(app) as client:
        call_id = reserve(client)
        with attach(client, call_id) as ws:
            assert dial(ws)['state'] == 'dialing'
            assert ws.receive_json()['event'] == 'error'
            assert client.delete('/v1/calls/' + call_id, headers=AUTH).status_code == 503
            assert ws.receive_json()['state'] == 'ending'
            assert provider.calls == 1
            path = '/twilio/status/' + call_id
            form = {'AccountSid': config().account, 'CallSid': SID, 'CallStatus': 'ringing'}
            signature = RequestValidator('server-secret').compute_signature('https://bridge.example.com' + path, form)
            assert client.post(path, data=form, headers={'X-Twilio-Signature': signature}).status_code == 200
            assert ws.receive_json()['event'] == 'ended'
        assert provider.hangups >= 1


def test_definitive_call_rejection_does_not_leave_a_paid_uncertain_session():
    class Rejected(Provider):
        async def dial(self, session):
            request = httpx.Request('POST', 'https://example.com')
            response = httpx.Response(403, request=request)
            raise httpx.HTTPStatusError('private credentials', request=request, response=response)
    app = create_app(config('twilio'), Rejected())
    with TestClient(app) as client:
        call_id = reserve(client)
        with attach(client, call_id) as ws:
            assert dial(ws)['state'] == 'dialing'
            assert ws.receive_json() == {'event': 'ended', 'reason': 'failed'}
        assert app.state.bridge.sessions[call_id].state == 'ended'


def test_connection_without_media_ends_after_deadline():
    provider = Provider()
    bridge = create_app(config('twilio'), provider).state.bridge
    session = Session(str(uuid.uuid4()), '+60123456789', state='answered', call_sid=SID,
                      dial_attempted=True, dial_at=time.monotonic()-61)
    bridge.sessions[session.id] = session
    asyncio.run(bridge.sweep())
    assert session.state == 'ended'
    assert provider.hangups == 1


def test_non_object_control_frame_closes_session_without_server_error():
    app = create_app(config())
    with TestClient(app) as client:
        call_id = reserve(client)
        with attach(client, call_id) as ws:
            dial(ws)
            ws.send_json(['invalid'])
            assert ws.receive_json()['event'] == 'ended'
        assert app.state.bridge.sessions[call_id].state == 'ended'
