# Phone Call Translator (电话同声传译)

A personal Android app that translates **real phone calls** in real time.
Built for cross-border business communication with suppliers and customers in
**Bangladesh (+880)** and **Malaysia (+60)**, where the two sides speak
different languages (Chinese ⇄ English).

> Personal side project by [FSFDJT](https://github.com/FSFDJT).
> This repository exists as part of the project's public documentation.

## What it does

- **Real-time phone call translation**: place a call through the gateway, the
  app listens to the other party's speech, recognizes it, translates it, and
  plays back the translated voice over the call — in both directions.
- **Speech pipeline** (via Alibaba Cloud):
  - Speech recognition: 阿里云百炼 Paraformer (realtime ASR)
  - Translation: 阿里云机器翻译 (ZH ⇄ EN)
  - Speech synthesis: on-device TTS (WAV)
- **Audio file import**: import recorded audio files, decode with MediaCodec,
  translate and play back in segments (supports long files / unlimited
  duration).
- **Gateway with two modes**:
  - `mock` mode: simulated phone bridge for local development and stress tests
  - `twilio` mode: real phone calls through **Twilio Programmable Voice +
    Media Streams** (call audio is streamed over WebSocket to the gateway)

## Architecture

```
Android App (Java/OkHttp/WebSocket)
      │  WebSocket audio stream (WSS, Bearer token)
      ▼
Gateway (Python FastAPI + uvicorn)
      │  Twilio Voice API / Media Streams
      ▼
Real phone network (Bangladesh / Malaysia)
```

The gateway validates configuration (E.164 numbers, allowed prefixes
`+880,+60`, max call duration), verifies Twilio request signatures, and
supports automatic reconnection and redialing.

## Tech stack

- **App**: Android, Java, OkHttp (keep-alive managed with `requestFresh`),
  WebSocket client, MediaCodec audio decode
- **Recognition/Translation**: 阿里云百炼 Paraformer realtime ASR, 阿里云机器翻译
- **Telephony gateway**: Twilio Programmable Voice + Media Streams
- **Gateway server**: Python FastAPI, uvicorn (WebSocket media streams),
  WebSocket-to-call audio bridging

## Repository layout

```
app/           Android application source (Java)
  src/main/java/com/example/facetofacetranslator/
gateway/       Python gateway server (FastAPI) — mock / twilio modes
tools/         Test & stress-test scripts (mock audio sender, text stress)
docs/          Setup guides (阿里云配置指南 / 电话网关配置指南)
```

## Security notes

- All credentials (Alibaba Cloud keys, gateway token) are **not** stored in
  this repository; they live in app-side encrypted storage and server
  environment variables.
- The gateway refuses to run in `twilio` mode without HTTPS origin and a valid
  Twilio Account SID (34-char `AC...`) / Auth Token.
- Before public distribution, long-term Alibaba AccessKeys should be moved
  server-side.

## Disclaimer

This is a personal learning/side project. It is not affiliated with or
endorsed by Twilio or Alibaba Cloud.
