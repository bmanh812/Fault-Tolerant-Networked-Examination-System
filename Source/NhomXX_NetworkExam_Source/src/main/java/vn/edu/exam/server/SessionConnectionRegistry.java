package vn.edu.exam.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.exam.common.protocol.MessageType;
import vn.edu.exam.common.protocol.PayloadCodec;
import vn.edu.exam.common.protocol.Payloads;
import vn.edu.exam.common.protocol.ProtocolIO;
import vn.edu.exam.common.protocol.ProtocolMessage;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class SessionConnectionRegistry implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionConnectionRegistry.class);
    private final ConcurrentMap<String, Set<ProtocolIO>> connections = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor notifications = new ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(128),
            task -> {
                Thread thread = new Thread(task, "exam-auto-submit-notifier");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
    );

    void bind(String sessionId, ProtocolIO protocol) {
        connections.compute(sessionId, (ignored, clients) -> {
            Set<ProtocolIO> active = clients == null ? ConcurrentHashMap.newKeySet() : clients;
            active.add(protocol);
            return active;
        });
    }

    void unbind(String sessionId, ProtocolIO protocol) {
        connections.computeIfPresent(sessionId, (ignored, clients) -> {
            clients.remove(protocol);
            return clients.isEmpty() ? null : clients;
        });
    }

    void broadcast(Payloads.AutoSubmit event) {
        Set<ProtocolIO> clients = connections.get(event.sessionId());
        if (clients == null) {
            return;
        }
        for (ProtocolIO client : clients) {
            try {
                notifications.execute(() -> push(client, event));
            } catch (RejectedExecutionException error) {
                LOGGER.warn("Could not queue auto-submit notification for session {}", event.sessionId());
            }
        }
    }

    void push(ProtocolIO client, Payloads.AutoSubmit event) {
        try {
            client.write(ProtocolMessage.push(MessageType.AUTO_SUBMIT, PayloadCodec.toMap(event)));
        } catch (IOException error) {
            LOGGER.debug("Could not notify session {} of auto-submit", event.sessionId(), error);
            unbind(event.sessionId(), client);
        }
    }

    @Override
    public void close() {
        notifications.shutdownNow();
    }
}
