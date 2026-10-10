package vn.edu.exam.common.protocol;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class ProtocolIO {
    private final InputStream input;
    private final OutputStream output;
    private final int maxMessageBytes;
    private final ObjectMapper mapper;

    public ProtocolIO(InputStream input, OutputStream output, int maxMessageBytes) {
        this.input = input;
        this.output = output;
        this.maxMessageBytes = maxMessageBytes;
        this.mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    public ProtocolMessage read() throws IOException {
        String json = FrameCodec.readFrame(input, maxMessageBytes);
        return json == null ? null : mapper.readValue(json, ProtocolMessage.class);
    }

    public synchronized void write(ProtocolMessage message) throws IOException {
        FrameCodec.writeFrame(output, mapper.writeValueAsString(message), maxMessageBytes);
    }
}

