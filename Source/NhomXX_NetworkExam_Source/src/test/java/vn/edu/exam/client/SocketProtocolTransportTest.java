package vn.edu.exam.client;

import org.junit.jupiter.api.Test;
import vn.edu.exam.common.protocol.PayloadCodec;
import vn.edu.exam.common.protocol.Payloads;
import vn.edu.exam.common.protocol.ProtocolIO;
import vn.edu.exam.common.protocol.ProtocolMessage;
import vn.edu.exam.common.protocol.ProtocolValidator;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static vn.edu.exam.common.protocol.MessageType.SUBMIT;

class SocketProtocolTransportTest {
    @Test
    void concurrentWritesRemainSeparateCompleteFrames() throws Exception {
        int requestCount = 40;
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
            Future<List<ProtocolMessage>> receivedFuture = serverExecutor.submit(() -> {
                try (Socket socket = serverSocket.accept()) {
                    socket.setSoTimeout(3_000);
                    ProtocolIO protocol = new ProtocolIO(
                            socket.getInputStream(),
                            socket.getOutputStream(),
                            65_536
                    );
                    List<ProtocolMessage> received = new ArrayList<>();
                    for (int index = 0; index < requestCount; index++) {
                        ProtocolMessage message = protocol.read();
                        ProtocolValidator.validateClientMessage(message);
                        received.add(message);
                    }
                    return received;
                }
            });

            ExecutorService writers = Executors.newFixedThreadPool(8);
            try (SocketProtocolTransport transport = SocketProtocolTransport.connect(
                    "127.0.0.1",
                    serverSocket.getLocalPort(),
                    1_000,
                    1_000,
                    65_536
            )) {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> writes = new ArrayList<>();
                for (int index = 0; index < requestCount; index++) {
                    int requestNumber = index;
                    writes.add(writers.submit(() -> {
                        start.await();
                        transport.write(ProtocolMessage.request(
                                SUBMIT,
                                PayloadCodec.toMap(new Payloads.SubmitRequest(
                                        "SESSION-" + requestNumber
                                ))
                        ));
                        return null;
                    }));
                }

                start.countDown();
                for (Future<?> write : writes) {
                    write.get(3, TimeUnit.SECONDS);
                }

                List<ProtocolMessage> received = receivedFuture.get(3, TimeUnit.SECONDS);
                Set<String> requestIds = new HashSet<>();
                for (ProtocolMessage message : received) {
                    requestIds.add(message.requestId());
                }
                assertEquals(requestCount, received.size());
                assertEquals(requestCount, requestIds.size());
            } finally {
                writers.shutdownNow();
                serverExecutor.shutdownNow();
            }
        }
    }
}
