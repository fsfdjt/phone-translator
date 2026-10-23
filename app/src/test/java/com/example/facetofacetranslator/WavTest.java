package com.example.facetofacetranslator;

import org.junit.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.assertEquals;

public class WavTest {
    @Test public void writesStandardMonoHeader() {
        byte[] header = Wav.header(64000);
        assertEquals(Wav.HEADER_SIZE, header.length);
        assertEquals('R', header[0]);
        assertEquals('I', header[1]);
        assertEquals('F', header[2]);
        assertEquals('F', header[3]);
        assertEquals('W', header[8]);
        assertEquals('A', header[9]);
        assertEquals('V', header[10]);
        assertEquals('E', header[11]);
        assertEquals('d', header[36]);
        assertEquals('a', header[37]);
        assertEquals('t', header[38]);
        assertEquals('a', header[39]);
    }

    @Test public void encodesSizesAndFormatLittleEndian() {
        byte[] header = Wav.header(64000);
        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(36 + 64000, buffer.getInt(4));
        assertEquals(1, buffer.getShort(20));
        assertEquals(1, buffer.getShort(22));
        assertEquals(16000, buffer.getInt(24));
        assertEquals(32000, buffer.getInt(28));
        assertEquals(2, buffer.getShort(32));
        assertEquals(16, buffer.getShort(34));
        assertEquals(64000, buffer.getInt(40));
    }
}
