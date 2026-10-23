"""Inject an English PCM WAV into an attached MOCK call, never a real phone call."""
import argparse
import json
import os
from pathlib import Path
from urllib.request import Request, urlopen

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('wav', type=Path)
    parser.add_argument('--url', default='http://127.0.0.1:8765')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    token = os.getenv('GATEWAY_TOKEN') or (root / '.toolchain/gateway-token.txt').read_text(encoding='utf-8').strip()
    auth = {'Authorization': 'Bearer ' + token}
    with urlopen(Request(args.url + '/v1/calls/active', headers=auth), timeout=10) as response:
        state = json.load(response)
    if state['mode'] != 'mock' or state['state'] != 'connected' or not state['callId']:
        raise SystemExit('First connect a mock call in the phone app. Real calls are not supported by this tool.')
    data = args.wav.read_bytes()
    if len(data) > 8*1024*1024:
        raise SystemExit('WAV is too large')
    request = Request(args.url + '/v1/calls/' + state['callId'] + '/mock/inbound', data=data,
                      headers={**auth, 'Content-Type': 'audio/wav'}, method='POST')
    with urlopen(request, timeout=75) as response:
        print(json.load(response))

if __name__ == '__main__':
    main()
