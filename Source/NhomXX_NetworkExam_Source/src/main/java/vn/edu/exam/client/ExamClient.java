package vn.edu.exam.client;

import vn.edu.exam.common.protocol.PayloadCodec;
import vn.edu.exam.common.protocol.Payloads;
import vn.edu.exam.common.protocol.ProtocolMessage;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static vn.edu.exam.common.protocol.MessageType.JOIN_EXAM;

public final class ExamClient implements AutoCloseable {
    private final AtomicReference<ClientState> state = new AtomicReference<>(ClientState.DISCONNECTED);
    private final ClientExamState examState = new ClientExamState();
    private ProtocolTransport transport;

    public void connect(
            String host,
            int port,
            int connectTimeoutMs,
            int readTimeoutMs,
            int maxMessageBytes
    ) throws IOException {
        if (!state.compareAndSet(ClientState.DISCONNECTED, ClientState.CONNECTING)) {
            throw new IllegalStateException("Client must be disconnected before connect");
        }
        try {
            transport = SocketProtocolTransport.connect(
                    host,
                    port,
                    connectTimeoutMs,
                    readTimeoutMs,
                    maxMessageBytes
            );
            state.set(ClientState.CONNECTED);
        } catch (IOException error) {
            state.set(ClientState.DISCONNECTED);
            throw error;
        }
    }

    public ProtocolMessage createJoinRequest(String candidateId, String examId) {
        return ProtocolMessage.request(
                JOIN_EXAM,
                PayloadCodec.toMap(new Payloads.JoinExamRequest(candidateId, examId))
        );
    }

    public ClientState state() {
        return state.get();
    }

    public ClientExamState examState() {
        return examState;
    }

    @Override
    public void close() throws IOException {
        state.set(ClientState.CLOSED);
        if (transport != null) {
            transport.close();
        }
    }
}
