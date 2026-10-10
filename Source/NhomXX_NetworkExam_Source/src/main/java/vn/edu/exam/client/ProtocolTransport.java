package vn.edu.exam.client;

import vn.edu.exam.common.protocol.ProtocolMessage;

import java.io.Closeable;
import java.io.IOException;

public interface ProtocolTransport extends Closeable {
    /** Returns {@code null} only when the peer reaches a clean EOF. */
    ProtocolMessage read() throws IOException;

    /** Implementations must keep complete protocol frames from interleaving. */
    void write(ProtocolMessage message) throws IOException;
}
