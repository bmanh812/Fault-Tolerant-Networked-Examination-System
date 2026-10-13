package vn.edu.exam.client;

import vn.edu.exam.common.protocol.MessageType;
import vn.edu.exam.common.protocol.ProtocolMessage;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Test-only injection: all traffic uses real TCP, except the first SUBMIT_ACK is discarded. */
public final class Phase8TestClient {
    private Phase8TestClient() {
    }

    public static ExamClient droppingFirstSubmitAck(Consumer<ProtocolMessage> written) {
        AtomicBoolean drop = new AtomicBoolean(true);
        return new ExamClient(new ClientTransportConfig(1_000, 100, 300, 65_536),
                (host, port, config) -> {
                    ProtocolTransport tcp = SocketProtocolTransport.connect(host, port,
                            config.connectTimeoutMs(), config.socketReadTimeoutMs(), config.maxMessageBytes());
                    return new ProtocolTransport() {
                        @Override
                        public ProtocolMessage read() throws IOException {
                            while (true) {
                                ProtocolMessage response = tcp.read();
                                if (response != null && response.type() == MessageType.SUBMIT_ACK
                                        && drop.compareAndSet(true, false)) {
                                    continue;
                                }
                                return response;
                            }
                        }

                        @Override
                        public void write(ProtocolMessage message) throws IOException {
                            written.accept(message);
                            tcp.write(message);
                        }

                        @Override
                        public void close() throws IOException {
                            tcp.close();
                        }
                    };
                });
    }
}
