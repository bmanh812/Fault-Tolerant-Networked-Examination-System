package vn.edu.exam.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.exam.common.protocol.MessageType;
import vn.edu.exam.common.protocol.ProtocolIO;
import vn.edu.exam.common.protocol.ProtocolMessage;

import java.io.IOException;
import java.net.Socket;
import java.time.Instant;
import java.util.Map;

final class ClientHandler implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientHandler.class);

    private final Socket socket;
    private final int maxFrameBytes;

    ClientHandler(Socket socket, int maxFrameBytes) {
        this.socket = socket;
        this.maxFrameBytes = maxFrameBytes;
    }

    @Override
    public void run() {
        String remote = String.valueOf(socket.getRemoteSocketAddress());
        LOGGER.info("Client connected: {}", remote);
        try (socket) {
            ProtocolIO protocol = new ProtocolIO(socket.getInputStream(), socket.getOutputStream(), maxFrameBytes);
            ProtocolMessage request;
            while ((request = protocol.read()) != null) {
                if (request.type() == MessageType.PING) {
                    protocol.write(new ProtocolMessage(
                            MessageType.PONG,
                            1,
                            request.requestId(),
                            request.sessionId(),
                            Instant.now().toEpochMilli(),
                            Map.of("status", "OK", "serverTime", Instant.now().toEpochMilli())
                    ));
                } else {
                    protocol.write(new ProtocolMessage(
                            MessageType.ERROR,
                            1,
                            request.requestId(),
                            request.sessionId(),
                            Instant.now().toEpochMilli(),
                            Map.of("code", "INVALID_REQUEST", "message", "Handler is not implemented yet")
                    ));
                }
            }
        } catch (IOException error) {
            LOGGER.warn("Client {} disconnected: {}", remote, error.getMessage());
        } finally {
            LOGGER.info("Client closed: {}", remote);
        }
    }
}

