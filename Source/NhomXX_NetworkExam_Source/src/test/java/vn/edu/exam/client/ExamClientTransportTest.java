package vn.edu.exam.client;

import org.junit.jupiter.api.Test;
import vn.edu.exam.common.protocol.PayloadCodec;
import vn.edu.exam.common.protocol.Payloads;
import vn.edu.exam.common.protocol.ProtocolIO;
import vn.edu.exam.common.protocol.ProtocolMessage;

import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static vn.edu.exam.common.protocol.MessageType.AUTO_SUBMIT;
import static vn.edu.exam.common.protocol.MessageType.SUBMIT;
import static vn.edu.exam.common.protocol.MessageType.SUBMIT_ACK;

class ExamClientTransportTest {
    private static final long NOW = 1_790_800_000_000L;

    @Test
    void sendsRequestAndReceivesMatchingResponseOverTcp() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
            CompletableFuture<Void> serverDone = CompletableFuture.runAsync(() -> {
                try (Socket socket = serverSocket.accept()) {
                    ProtocolIO protocol = new ProtocolIO(
                            socket.getInputStream(),
                            socket.getOutputStream(),
                            65_536
                    );
                    ProtocolMessage request = protocol.read();
                    protocol.write(submitAck(request));
                } catch (IOException error) {
                    throw new RuntimeException(error);
                }
            }, serverExecutor);

            ClientTransportConfig config = new ClientTransportConfig(1_000, 200, 1_000, 65_536);
            try (ExamClient client = new ExamClient(config)) {
                client.connect("127.0.0.1", serverSocket.getLocalPort());
                ProtocolMessage request = submitRequest("SESSION-001");

                ProtocolMessage response = client.sendRequest(request).get(2, TimeUnit.SECONDS);

                assertEquals(SUBMIT_ACK, response.type());
                assertEquals(request.requestId(), response.requestId());
                serverDone.get(2, TimeUnit.SECONDS);
            } finally {
                serverExecutor.shutdownNow();
            }
        }
    }

    @Test
    void unrelatedResponseDoesNotCompletePendingRequest() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = connectedClient(transport, 1_000)) {
            ProtocolMessage request = submitRequest("SESSION-001");
            CompletableFuture<ProtocolMessage> future = client.sendRequest(request);
            transport.takeWritten();
            transport.receive(ProtocolMessage.request(
                    SUBMIT_ACK,
                    PayloadCodec.toMap(new Payloads.SubmitAck("OTHER", "SUBMITTED", NOW))
            ));

            assertThrows(TimeoutException.class, () -> future.get(50, TimeUnit.MILLISECONDS));
            assertEquals(1, client.pendingRequestCount());

            transport.receive(submitAck(request));
            assertEquals(request.requestId(), future.get(1, TimeUnit.SECONDS).requestId());
        }
    }

    @Test
    void requestTimeoutRemovesOnlyThatPendingRequest() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = connectedClient(transport, 60)) {
            CompletableFuture<ProtocolMessage> future = client.sendRequest(submitRequest("SESSION-001"));

            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> future.get(1, TimeUnit.SECONDS)
            );
            assertInstanceOf(TimeoutException.class, failure.getCause());
            assertEquals(0, client.pendingRequestCount());
            assertTrue(client.readerThreadAlive());
            assertEquals(ClientState.CONNECTED, client.state());
        }
    }

    @Test
    void socketReadTimeoutDoesNotStopReaderThread() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = connectedClient(transport, 1_000)) {
            ProtocolMessage request = submitRequest("SESSION-001");
            CompletableFuture<ProtocolMessage> response = client.sendRequest(request);

            transport.failRead(new SocketTimeoutException("No data yet"));
            transport.receive(submitAck(request));

            assertEquals(SUBMIT_ACK, response.get(1, TimeUnit.SECONDS).type());
            assertEquals(ClientState.CONNECTED, client.state());
            assertTrue(client.readerThreadAlive());
        }
    }

    @Test
    void eofDisconnectsAndFailsEveryPendingRequest() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = connectedClient(transport, 1_000)) {
            CompletableFuture<ProtocolMessage> first = client.sendRequest(submitRequest("SESSION-001"));
            CompletableFuture<ProtocolMessage> second = client.sendRequest(submitRequest("SESSION-002"));

            transport.signalEof();

            assertFutureFailsWith(first, EOFException.class);
            assertFutureFailsWith(second, EOFException.class);
            await(() -> client.state() == ClientState.DISCONNECTED);
            assertEquals(0, client.pendingRequestCount());
        }
    }

    @Test
    void closeIsIdempotentAndStopsReaderAndPendingRequests() throws Exception {
        FakeTransport transport = new FakeTransport();
        ExamClient client = connectedClient(transport, 1_000);
        CompletableFuture<ProtocolMessage> pending = client.sendRequest(submitRequest("SESSION-001"));

        client.close();
        client.close();
        client.disconnect();

        assertEquals(ClientState.CLOSED, client.state());
        assertTrue(transport.isClosed());
        assertFalse(client.readerThreadAlive());
        assertEquals(0, client.pendingRequestCount());
        assertTrue(pending.isCompletedExceptionally());
        assertThrows(CancellationException.class, pending::join);
    }

    @Test
    void disconnectStopsTransportButDoesNotCloseClientPermanently() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = connectedClient(transport, 1_000)) {
            CompletableFuture<ProtocolMessage> pending = client.sendRequest(submitRequest("SESSION-001"));

            client.disconnect();

            assertEquals(ClientState.DISCONNECTED, client.state());
            assertTrue(transport.isClosed());
            assertFalse(client.readerThreadAlive());
            assertEquals(0, client.pendingRequestCount());
            assertTrue(pending.isCompletedExceptionally());
        }
    }

    @Test
    void correlatesMultiplePendingRequestsWhenResponsesArriveOutOfOrder() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = connectedClient(transport, 1_000)) {
            ProtocolMessage firstRequest = submitRequest("SESSION-001");
            ProtocolMessage secondRequest = submitRequest("SESSION-002");
            ProtocolMessage thirdRequest = submitRequest("SESSION-003");
            CompletableFuture<ProtocolMessage> first = client.sendRequest(firstRequest);
            CompletableFuture<ProtocolMessage> second = client.sendRequest(secondRequest);
            CompletableFuture<ProtocolMessage> third = client.sendRequest(thirdRequest);

            transport.receive(submitAck(thirdRequest));
            transport.receive(submitAck(firstRequest));
            transport.receive(submitAck(secondRequest));

            assertEquals(firstRequest.requestId(), first.get(1, TimeUnit.SECONDS).requestId());
            assertEquals(secondRequest.requestId(), second.get(1, TimeUnit.SECONDS).requestId());
            assertEquals(thirdRequest.requestId(), third.get(1, TimeUnit.SECONDS).requestId());
            assertEquals(0, client.pendingRequestCount());
        }
    }

    @Test
    void routesAutoSubmitToPushHandlerWithoutCompletingRequestFuture() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = connectedClient(transport, 1_000)) {
            CompletableFuture<ProtocolMessage> receivedPush = new CompletableFuture<>();
            client.setServerPushHandler(receivedPush::complete);
            ProtocolMessage request = submitRequest("SESSION-001");
            CompletableFuture<ProtocolMessage> response = client.sendRequest(request);

            transport.receive(ProtocolMessage.push(
                    AUTO_SUBMIT,
                    PayloadCodec.toMap(new Payloads.AutoSubmit(
                            "SESSION-001", "AUTO_SUBMITTED", NOW
                    ))
            ));

            assertEquals(AUTO_SUBMIT, receivedPush.get(1, TimeUnit.SECONDS).type());
            assertFalse(response.isDone());
            assertEquals(1, client.pendingRequestCount());

            transport.receive(submitAck(request));
            assertEquals(SUBMIT_ACK, response.get(1, TimeUnit.SECONDS).type());
        }
    }

    private static ExamClient connectedClient(FakeTransport transport, int requestTimeoutMs) throws IOException {
        ClientTransportConfig config = new ClientTransportConfig(500, 100, requestTimeoutMs, 65_536);
        ExamClient client = new ExamClient(config, (host, port, ignored) -> transport);
        client.connect("test-host", 9090);
        return client;
    }

    private static ProtocolMessage submitRequest(String sessionId) {
        return ProtocolMessage.request(
                SUBMIT,
                PayloadCodec.toMap(new Payloads.SubmitRequest(sessionId))
        );
    }

    private static ProtocolMessage submitAck(ProtocolMessage request) {
        String sessionId = String.valueOf(request.payload().get("sessionId"));
        return ProtocolMessage.response(
                SUBMIT_ACK,
                request,
                PayloadCodec.toMap(new Payloads.SubmitAck(sessionId, "SUBMITTED", NOW))
        );
    }

    private static void assertFutureFailsWith(
            CompletableFuture<ProtocolMessage> future,
            Class<? extends Throwable> expectedType
    ) {
        ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> future.get(1, TimeUnit.SECONDS)
        );
        assertInstanceOf(expectedType, failure.getCause());
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean(), "Condition was not met before timeout");
    }

    private static final class FakeTransport implements ProtocolTransport {
        private static final Object EOF = new Object();

        private final BlockingQueue<Object> incoming = new LinkedBlockingQueue<>();
        private final BlockingQueue<ProtocolMessage> written = new LinkedBlockingQueue<>();
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public ProtocolMessage read() throws IOException {
            try {
                Object next = incoming.take();
                if (next == EOF) {
                    return null;
                }
                if (next instanceof IOException error) {
                    throw error;
                }
                return (ProtocolMessage) next;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("Reader interrupted", error);
            }
        }

        @Override
        public void write(ProtocolMessage message) throws IOException {
            if (closed.get()) {
                throw new IOException("Transport closed");
            }
            written.add(message);
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                incoming.offer(EOF);
            }
        }

        void receive(ProtocolMessage message) {
            incoming.add(message);
        }

        void signalEof() {
            incoming.add(EOF);
        }

        void failRead(IOException error) {
            incoming.add(error);
        }

        ProtocolMessage takeWritten() throws InterruptedException {
            return written.poll(1, TimeUnit.SECONDS);
        }

        boolean isClosed() {
            return closed.get();
        }
    }
}
