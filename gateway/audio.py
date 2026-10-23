"""PCM WAV <-> G.711 mu-law without audioop (Python 3.13+)."""
import io
import math
import struct
import wave

MAX_WAV = 8 * 1024 * 1024
MAX_SECONDS = 60


def encode_sample(sample: int) -> int:
    sign = 0x80 if sample < 0 else 0
    sample = min(abs(sample), 32635) + 132
    exponent = max(0, sample.bit_length() - 8)
    mantissa = (sample >> (exponent + 3)) & 15
    return (~(sign | exponent << 4 | mantissa)) & 255


def decode_sample(value: int) -> int:
    value = (~value) & 255
    sample = (((value & 15) << 3) + 132) << ((value >> 4) & 7)
    sample -= 132
    return -sample if value & 128 else sample


def inbound_pcm(payload: bytes) -> bytes:
    # Each 8 kHz sample appears twice: continuous 16 kHz signed mono PCM.
    return b''.join(struct.pack('<hh', decode_sample(v), decode_sample(v)) for v in payload)


def wav_to_mulaw(data: bytes) -> bytes:
    if not data or len(data) > MAX_WAV:
        raise ValueError('audio_size')
    try:
        with wave.open(io.BytesIO(data), 'rb') as wav:
            channels, width, rate, frames = (wav.getnchannels(), wav.getsampwidth(),
                                           wav.getframerate(), wav.getnframes())
            if channels not in (1, 2) or width != 2 or not 8000 <= rate <= 48000:
                raise ValueError('wav_format')
            if not 0 < frames <= rate * MAX_SECONDS or wav.getcomptype() != 'NONE':
                raise ValueError('audio_duration')
            raw = wav.readframes(frames)
            if len(raw) != frames * channels * width:
                raise ValueError('truncated_wav')
    except (wave.Error, EOFError, struct.error) as exc:
        raise ValueError('wav_format') from exc
    values = struct.unpack('<' + 'h' * (len(raw) // 2), raw)
    mono = values if channels == 1 else [(values[i] + values[i+1]) / 2 for i in range(0, len(values), 2)]
    # Box average when downsampling avoids folding high frequencies into phone speech.
    count = math.ceil(frames * 8000 / rate)
    result = bytearray()
    for i in range(count):
        start, end = i * rate // 8000, min(frames, (i+1) * rate // 8000)
        result.append(encode_sample(round(sum(mono[start:end]) / (end-start))))
    return bytes(result)
