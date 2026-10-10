package vn.edu.exam.client;

import vn.edu.exam.common.protocol.ProtocolMessage;

import java.io.Closeable;
import java.io.IOException;

public interface ProtocolTransport extends Closeable {
    ProtocolMessage read() throws IOException;

    void write(ProtocolMessage message) throws IOException;
}
