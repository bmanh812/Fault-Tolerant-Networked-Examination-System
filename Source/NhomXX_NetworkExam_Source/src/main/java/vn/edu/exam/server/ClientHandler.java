package vn.edu.exam.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.exam.common.protocol.ErrorCode;
import vn.edu.exam.common.protocol.PayloadCodec;
import vn.edu.exam.common.protocol.Payloads;
import vn.edu.exam.common.protocol.ProtocolIO;
import vn.edu.exam.common.protocol.ProtocolMessage;
import vn.edu.exam.common.protocol.ProtocolValidator;

import java.io.IOException;
import java.net.Socket;

import static vn.edu.exam.common.protocol.MessageType.ERROR;

final class ClientHandler implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientHandler.class);

    private final Socket socket;
    private final int maxMessageBytes;

    ClientHandler(Socket socket, int maxMessageBytes) {
        this.socket = socket;
        this.maxMessageBytes = maxMessageBytes;
    }

    @Override
    public void run() {
        String remote = String.valueOf(socket.getRemoteSocketAddress());
        LOGGER.info("Client connected: {}", remote);
        try (socket) {
            ProtocolIO protocol = new ProtocolIO(
                    socket.getInputStream(),
                    socket.getOutputStream(),
                    maxMessageBytes
            );
            ProtocolMessage request;
            while ((request = protocol.read()) != null) {
                ProtocolValidator.validateClientMessage(request);
                protocol.write(notImplemented(request));
            }
        } catch (IllegalArgumentException error) {
            LOGGER.warn("Invalid message from {}: {}", remote, error.getMessage());
        } catch (IOException error) {
            LOGGER.info("Client {} disconnected: {}", remote, error.getMessage());
        } catch (RuntimeException error) {
            LOGGER.error("Client handler failed for {}", remote, error);
        } finally {
            LOGGER.info("Client closed: {}", remote);
        }
    }

    private ProtocolMessage notImplemented(ProtocolMessage request) {
        Payloads.ErrorResponse error = new Payloads.ErrorResponse(
                ErrorCode.INVALID_REQUEST,
                "Business handler for " + request.type() + " is not implemented yet",
                null
        );
        return ProtocolMessage.response(ERROR, request, PayloadCodec.toMap(error));
    }
}

