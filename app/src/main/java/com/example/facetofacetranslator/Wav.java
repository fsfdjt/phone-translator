package com.example.facetofacetranslator;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Minimal PCM WAV header writer. Kept as plain Java so it can be verified on the host. */
final class Wav {
    static final int SAMPLE_RATE = 16000;
    static final int CHANNELS = 1;
    static final int BITS_PER_SAMPLE = 16;
    static final int HEADER_SIZE = 44;
    private static final int PCM = 1;

    static byte[] header(int dataLength) {
        return header(SAMPLE_RATE, CHANNELS, BITS_PER_SAMPLE, dataLength);
    }

    static byte[] header(int sampleRate, int channels, int bitsPerSample, int dataLength) {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 'R').put((byte) 'I').put((byte) 'F').put((byte) 'F');
        buffer.putInt(36 + dataLength);
        buffer.put((byte) 'W').put((byte) 'A').put((byte) 'V').put((byte) 'E');
        buffer.put((byte) 'f').put((byte) 'm').put((byte) 't').put((byte) ' ');
        buffer.putInt(16);
        buffer.putShort((short) PCM);
        buffer.putShort((short) channels);
        buffer.putInt(sampleRate);
        buffer.putInt(sampleRate * channels * bitsPerSample / 8);
        buffer.putShort((short) (channels * bitsPerSample / 8));
        buffer.putShort((short) bitsPerSample);
        buffer.put((byte) 'd').put((byte) 'a').put((byte) 't').put((byte) 'a');
        buffer.putInt(dataLength);
        return buffer.array();
    }

    static void writeSizes(RandomAccessFile file, int dataLength) throws IOException {
        byte[] header = header(dataLength);
        file.seek(4);
        file.write(header, 4, 4);
        file.seek(40);
        file.write(header, 40, 4);
    }

    private Wav() {}
}
