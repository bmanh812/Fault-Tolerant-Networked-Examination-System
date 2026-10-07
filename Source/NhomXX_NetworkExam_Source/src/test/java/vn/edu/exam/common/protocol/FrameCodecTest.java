package vn.edu.exam.common.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FrameCodecTest {
    @Test
    void roundTripsUnicodeJson() throws Exception {
        String json = "{\"message\":\"Xin chào thí sinh\"}";
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        FrameCodec.writeFrame(output, json, 65_536);

        assertEquals(json, FrameCodec.readFrame(new ByteArrayInputStream(output.toByteArray()), 65_536));
    }

    @Test
    void rejectsFrameOverConfiguredLimit() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        FrameCodec.writeFrame(output, "12345", 10);

        assertThrows(IOException.class,
                () -> FrameCodec.readFrame(new ByteArrayInputStream(output.toByteArray()), 4));
    }

    @Test
    void readsFrameWhenTcpDataArrivesInSmallChunks() throws Exception {
        String json = "{\"type\":\"PING\",\"message\":\"du lieu bi chia nho\"}";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        FrameCodec.writeFrame(output, json, 65_536);

        InputStream chunked = new FilterInputStream(new ByteArrayInputStream(output.toByteArray())) {
            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                return super.read(buffer, offset, Math.min(length, 2));
            }
        };

        assertEquals(json, FrameCodec.readFrame(chunked, 65_536));
    }

    @Test
    void readsConsecutiveFramesFromOneTcpStream() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        FrameCodec.writeFrame(output, "{\"seq\":1}", 65_536);
        FrameCodec.writeFrame(output, "{\"seq\":2}", 65_536);
        ByteArrayInputStream input = new ByteArrayInputStream(output.toByteArray());

        assertEquals("{\"seq\":1}", FrameCodec.readFrame(input, 65_536));
        assertEquals("{\"seq\":2}", FrameCodec.readFrame(input, 65_536));
    }
}

