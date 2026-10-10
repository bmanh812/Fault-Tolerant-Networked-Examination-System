package vn.edu.exam.client;

import vn.edu.exam.common.protocol.ProtocolIO;
import vn.edu.exam.common.protocol.ProtocolMessage;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SocketProtocolTransport implements ProtocolTransport {
    private final Socket socket;
    private final ProtocolIO protocol;
    private final Object writeLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();

    private SocketProtocolTransport(Socket socket, ProtocolIO protocol) {
        this.socket = socket;
        this.protocol = protocol;
    }

    public static SocketProtocolTransport connect(
            String host,
            int port,
            int connectTimeoutMs,
            int readTimeoutMs,
            int maxMessageBytes
    ) throws IOException {
        Objects.requireNonNull(host, "host is required");
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);
            socket.setTcpNoDelay(true);
            return new SocketProtocolTransport(
                    socket,
                    new ProtocolIO(socket.getInputStream(), socket.getOutputStream(), maxMessageBytes)
            );
        } catch (IOException | RuntimeException error) {
            try {
                socket.close();
            } catch (IOException closeError) {
                error.addSuppressed(closeError);
            }
            throw error;
        }
    }

    @Override
    public ProtocolMessage read() throws IOException {
        return protocol.read();
    }

    @Override
    public void write(ProtocolMessage message) throws IOException {
        Objects.requireNonNull(message, "message is required");
        synchronized (writeLock) {
            if (closed.get()) {
                throw new IOException("Transport is closed");
            }
            protocol.write(message);
        }
    }

    @Override
    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            socket.close();
        }
    }
}
