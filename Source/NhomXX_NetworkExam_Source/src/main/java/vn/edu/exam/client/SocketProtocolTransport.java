package vn.edu.exam.client;

import vn.edu.exam.common.protocol.ProtocolIO;
import vn.edu.exam.common.protocol.ProtocolMessage;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

public final class SocketProtocolTransport implements ProtocolTransport {
    private final Socket socket;
    private final ProtocolIO protocol;

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
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        socket.setSoTimeout(readTimeoutMs);
        return new SocketProtocolTransport(
                socket,
                new ProtocolIO(socket.getInputStream(), socket.getOutputStream(), maxMessageBytes)
        );
    }

    @Override
    public ProtocolMessage read() throws IOException {
        return protocol.read();
    }

    @Override
    public void write(ProtocolMessage message) throws IOException {
        protocol.write(message);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
