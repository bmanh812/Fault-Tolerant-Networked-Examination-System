package vn.edu.exam.common.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FrameCodecTest {
    @Test
    void roundTripsUnicodeJson() throws Exception {
        String json = "{\"message\":\"Xin chao thi sinh\"}";
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
}

