package com.example.facetofacetranslator;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.media.AudioFormat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Decodes any playable audio (mp3/m4a/ogg/…, including Google Drive SAF files) to 16 kHz mono 16-bit PCM WAV. */
final class DecodeUtil {
    private DecodeUtil() {}

    static final long MAX_BYTES = 64L * 1024 * 1024;

    /** Decodes uri into a temp WAV file (16 kHz mono 16-bit PCM). Throws with friendly messages. */
    static File decodeToWav(Context context, Uri uri) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, uri, null);
        } catch (Exception error) {
            throw new IOException("无法读取该音频文件");
        }
        int trackIndex = -1;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat fmt = extractor.getTrackFormat(i);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) { trackIndex = i; break; }
        }
        if (trackIndex < 0) { extractor.release(); throw new IOException("文件中没有音频轨道"); }
        extractor.selectTrack(trackIndex);
        MediaFormat inputFormat = extractor.getTrackFormat(trackIndex);
        String mime = inputFormat.getString(MediaFormat.KEY_MIME);
        if (!isSupported(mime)) { extractor.release(); throw new IOException("不支持的音频格式：" + mime); }
        MediaCodec codec = null;
        try {
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(inputFormat, null, null, 0);
            codec.start();
            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            long durationUs = 0;
            while (!outputDone) {
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(10_000);
                    if (inIndex >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(inIndex);
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int outIndex = codec.dequeueOutputBuffer(info, 10_000);
                if (outIndex >= 0) {
                    if (info.size > 0) {
                        ByteBuffer out = codec.getOutputBuffer(outIndex);
                        out.position(info.offset);
                        out.limit(info.offset + info.size);
                        byte[] bytes = new byte[info.size];
                        out.get(bytes);
                        pcm.write(bytes);
                        durationUs = info.presentationTimeUs;
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // output format may change; sample-rate/channels read after loop via outputFormat()
                }
            }
            MediaFormat outputFormat = codec.getOutputFormat();
            int sampleRate = outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 16000;
            int channels = outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            int encoding = outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
                    ? outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
            byte[] raw = pcm.toByteArray();
            byte[] mono16 = toMono16(raw, channels, encoding, sampleRate);
            if (mono16.length > MAX_BYTES) throw new IOException("音频超过 64MB，请截短后再导入");
            File temp = File.createTempFile("imported-", ".wav", context.getCacheDir());
            writeWav(temp, mono16, 16000);
            return temp;
        } finally {
            if (codec != null) { try { codec.stop(); } catch (Exception ignored) {} codec.release(); }
            extractor.release();
        }
    }

    private static boolean isSupported(String mime) {
        return mime != null && (mime.startsWith("audio/mp4") || mime.startsWith("audio/mpeg")
                || mime.startsWith("audio/ogg") || mime.startsWith("audio/wav")
                || mime.startsWith("audio/x-wav") || mime.startsWith("audio/flac")
                || mime.startsWith("audio/amr") || mime.startsWith("audio/aac")
                || mime.startsWith("audio/3gpp") || mime.startsWith("audio/x-m4a")
                || mime.startsWith("audio/x-ms-wma") || mime.startsWith("audio/raw"));
    }

    /** Converts raw decoder output to 16 kHz mono signed 16-bit little-endian PCM (linear downmix + resample). */
    private static byte[] toMono16(byte[] raw, int channels, int encoding, int sampleRate) {
        if (raw == null || raw.length == 0) return raw;
        boolean floatPcm = encoding == AudioFormat.ENCODING_PCM_FLOAT;
        // sample frames of source
        int bytesPerSample = floatPcm ? 4 : 2;
        long frames = raw.length / (bytesPerSample * Math.max(1, channels));
        if (frames <= 0) return new byte[0];
        // mono float samples at source rate
        float[] mono = new float[(int) frames];
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        for (int f = 0; f < frames; f++) {
            float sum = 0;
            for (int c = 0; c < channels; c++) {
                float v;
                if (floatPcm) v = buffer.getFloat();
                else v = buffer.getShort() / 32768f;
                sum += v;
            }
            mono[f] = channels > 1 ? sum / channels : sum;
        }
        // resample to 16000 Hz linear interpolation
        int targetFrames = (int) Math.max(1, (long) frames * 16000 / Math.max(1, sampleRate));
        short[] out = new short[targetFrames];
        for (int i = 0; i < targetFrames; i++) {
            double pos = (double) i * (frames - 1) / Math.max(1, targetFrames - 1);
            int low = (int) pos;
            int high = (int) Math.min(frames - 1, low + 1);
            double frac = pos - low;
            float value = (float) (mono[low] * (1 - frac) + mono[high] * frac);
            int s = (int) (value * 32767);
            if (s > 32767) s = 32767;
            if (s < -32768) s = -32768;
            out[i] = (short) s;
        }
        ByteBuffer result = ByteBuffer.allocate(out.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short s : out) result.putShort(s);
        return result.array();
    }

    private static void writeWav(File file, byte[] pcm16, int sampleRate) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            int dataLength = pcm16.length;
            ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            header.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            header.putInt(36 + dataLength);
            header.put("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            header.put("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            header.putInt(16);
            header.putShort((short) 1);
            header.putShort((short) 1);
            header.putInt(sampleRate);
            header.putInt(sampleRate * 2);
            header.putShort((short) 2);
            header.putShort((short) 16);
            header.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            header.putInt(dataLength);
            output.write(header.array());
            output.write(pcm16);
        }
    }
}
