package vn.edu.exam.client.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.exam.client.AnswerDraft;
import vn.edu.exam.client.ClientExamState;
import vn.edu.exam.client.ClientState;
import vn.edu.exam.client.ClientTransportConfig;
import vn.edu.exam.client.ExamClient;
import vn.edu.exam.server.persistence.DatabaseManager;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JRadioButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Automated smoke of the actual entry points and Swing controls, with an isolated file DB. */
class Phase8SwingSmokeTest {
    @TempDir
    Path temporary;

    @Test
    void databaseInitServerMainAndSwingManualSubmit() throws Exception {
        smoke(false);
    }

    @Test
    void databaseInitServerMainAndSwingScheduledAutoSubmit() throws Exception {
        smoke(true);
    }

    private void smoke(boolean auto) throws Exception {
        int port;
        try (ServerSocket reserved = new ServerSocket(0)) {
            port = reserved.getLocalPort();
        }
        Files.createDirectories(temporary.resolve("config"));
        Files.createDirectories(temporary.resolve("data"));
        Files.copy(Path.of("data", "schema.sql"), temporary.resolve("data/schema.sql"));
        Files.copy(Path.of("data", "sample-data.sql"), temporary.resolve("data/sample-data.sql"));
        Files.writeString(temporary.resolve("config/application.properties"), """
                server.port=%d
                server.workerThreads=2
                server.queueCapacity=8
                server.socketReadTimeoutMs=0
                protocol.maxMessageBytes=65536
                database.url=jdbc:h2:file:./data/smoke
                database.user=sa
                database.password=
                database.schema=data/schema.sql
                database.seed=data/sample-data.sql
                """.formatted(port));
        Process init = launch("vn.edu.exam.server.persistence.DatabaseInitMain")
                .redirectOutput(temporary.resolve("init.log").toFile()).start();
        try {
            assertTrue(init.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, init.exitValue(), Files.readString(temporary.resolve("init.log")));
        } finally {
            stop(init);
        }

        DatabaseManager database = new DatabaseManager(
                "jdbc:h2:file:" + temporary.resolve("data/smoke").toString().replace('\\', '/'), "sa", "");
        long now = System.currentTimeMillis();
        try (Connection connection = database.open(); PreparedStatement update = connection.prepareStatement(
                "UPDATE exam SET start_time = ?, end_time = ? WHERE exam_id = 'EXAM-001'")) {
            update.setLong(1, now - 1_000);
            update.setLong(2, now + (auto ? 6_000 : 60_000));
            assertEquals(1, update.executeUpdate());
        }

        Process server = launch("vn.edu.exam.server.ServerMain").start();
        CompletableFuture<Void> listening = new CompletableFuture<>();
        StringBuilder serverLog = new StringBuilder();
        Thread logReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    server.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    serverLog.append(line).append('\n');
                    if (line.contains("listening on port")) {
                        listening.complete(null);
                    }
                }
                if (!listening.isDone()) {
                    listening.completeExceptionally(new IllegalStateException(serverLog.toString()));
                }
            } catch (Exception error) {
                listening.completeExceptionally(error);
            }
        }, "phase8-smoke-server-log");
        logReader.setDaemon(true);
        logReader.start();

        ExamClient core = new ExamClient(new ClientTransportConfig(1_000, 100, 2_000, 65_536));
        ExecutorService network = Executors.newSingleThreadExecutor();
        AtomicReference<SwingExamFrame> frame = new AtomicReference<>();
        AtomicReference<RecordingView> view = new AtomicReference<>();
        AtomicReference<ExamUiController> controller = new AtomicReference<>();
        try {
            listening.get(5, TimeUnit.SECONDS);
            SwingUtilities.invokeAndWait(() -> {
                SwingExamFrame created = new SwingExamFrame("127.0.0.1", port, "CAND-001", "EXAM-001");
                RecordingView recording = new RecordingView(created);
                ExamUiController control = new ExamUiController(
                        new CoreExamClientGateway(core), recording, network, SwingUtilities::invokeLater);
                created.bind(control);
                created.setVisible(true);
                frame.set(created);
                view.set(recording);
                controller.set(control);
                button(created, "Tham gia thi").doClick();
            });
            view.get().joined.get(3, TimeUnit.SECONDS);
            SwingUtilities.invokeAndWait(() -> {
                List<JRadioButton> choices = components(frame.get(), JRadioButton.class);
                choices.get(0).doClick();
                choices.get(4).doClick();
            });
            view.get().savedQ1.get(3, TimeUnit.SECONDS);
            view.get().savedQ2.get(3, TimeUnit.SECONDS);
            if (!auto) {
                CompletableFuture<Void> dialogClosed = new CompletableFuture<>();
                SwingUtilities.invokeLater(() -> {
                    button(frame.get(), "Nộp bài").doClick();
                    dialogClosed.complete(null);
                });
                // The modal dialog runs a nested event loop, so this event confirms the real dialog.
                SwingUtilities.invokeLater(() -> {
                    for (Window window : Window.getWindows()) {
                        if (window instanceof JDialog dialog && dialog.isShowing()) {
                            List<JOptionPane> panes = components(dialog, JOptionPane.class);
                            if (!panes.isEmpty()) {
                                panes.get(0).setValue(JOptionPane.YES_OPTION);
                            }
                        }
                    }
                });
                dialogClosed.get(3, TimeUnit.SECONDS);
            }
            SubmissionDisplayStatus finalStatus = view.get().submitted.get(10, TimeUnit.SECONDS);
            assertEquals(auto ? SubmissionDisplayStatus.AUTO_SUBMITTED : SubmissionDisplayStatus.SUBMITTED, finalStatus);
            SwingUtilities.invokeAndWait(() -> {
                assertFalse(button(frame.get(), "Nộp bài").isEnabled());
                assertTrue(components(frame.get(), JRadioButton.class).stream().noneMatch(Component::isEnabled));
                assertEquals(1, view.get().timerStops);
                assertFalse(tickerRunning(frame.get()));
            });
            assertEquals(auto ? ClientState.AUTO_SUBMITTED : ClientState.SUBMITTED, core.state());
        } finally {
            if (controller.get() != null) {
                controller.get().close();
            } else {
                core.close();
                network.shutdownNow();
            }
            SwingUtilities.invokeAndWait(() -> {
                if (frame.get() != null) {
                    frame.get().dispatchEvent(new java.awt.event.WindowEvent(
                            frame.get(), java.awt.event.WindowEvent.WINDOW_CLOSING));
                    frame.get().dispose();
                }
            });
            stop(server);
            logReader.join(3_000);
            assertFalse(logReader.isAlive());
            assertTrue(network.awaitTermination(3, TimeUnit.SECONDS));
        }
        // Server process is stopped before reopening the isolated file database.
        try (Connection connection = database.open(); PreparedStatement query = connection.prepareStatement(
                "SELECT s.submit_type, s.submitted_at, e.status FROM submission s "
                        + "JOIN exam_session e ON e.session_id=s.session_id WHERE e.candidate_id='CAND-001'")) {
            try (ResultSet result = query.executeQuery()) {
                assertTrue(result.next());
                assertEquals(auto ? "AUTO" : "MANUAL", result.getString(1));
                assertTrue(result.getLong(2) > 0);
                assertEquals(auto ? "AUTO_SUBMITTED" : "SUBMITTED", result.getString(3));
                assertFalse(result.next());
            }
        }
    }

    private ProcessBuilder launch(String mainClass) {
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", classPath, mainClass).directory(temporary.toFile()).redirectErrorStream(true);
    }

    private static void stop(Process process) throws InterruptedException {
        if (process.isAlive()) {
            process.destroy();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue(process.waitFor(3, TimeUnit.SECONDS));
            }
        }
    }

    private static boolean tickerRunning(SwingExamFrame frame) {
        try {
            java.lang.reflect.Field field = SwingExamFrame.class.getDeclaredField("countdownTimer");
            field.setAccessible(true);
            Object timer = field.get(frame);
            java.lang.reflect.Field ticker = ExamCountdownTimer.class.getDeclaredField("ticker");
            ticker.setAccessible(true);
            return ((ExamCountdownTimer.Ticker) ticker.get(timer)).isRunning();
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static JButton button(Container root, String text) {
        return components(root, JButton.class).stream().filter(value -> text.equals(value.getText()))
                .findFirst().orElseThrow();
    }

    private static <T extends Component> List<T> components(Container root, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)) {
                result.add(type.cast(component));
            }
            if (component instanceof Container container) {
                result.addAll(components(container, type));
            }
        }
        return result;
    }

    private static final class RecordingView implements ExamUiView {
        private final SwingExamFrame delegate;
        private final CompletableFuture<Void> joined = new CompletableFuture<>();
        private final CompletableFuture<Void> savedQ1 = new CompletableFuture<>();
        private final CompletableFuture<Void> savedQ2 = new CompletableFuture<>();
        private final CompletableFuture<SubmissionDisplayStatus> submitted = new CompletableFuture<>();
        private int timerStops;

        private RecordingView(SwingExamFrame delegate) { this.delegate = delegate; }

        @Override public void showJoinInProgress() { delegate.showJoinInProgress(); }
        @Override public void showJoinError(String message) {
            delegate.showJoinError(message);
            joined.completeExceptionally(new IllegalStateException(message));
        }
        @Override public void showExam(ClientExamState.Snapshot exam) {
            delegate.showExam(exam);
            joined.complete(null);
        }
        @Override public void showAnswerState(String id, AnswerDraft answer, AnswerDisplayStatus status) {
            assertTrue(SwingUtilities.isEventDispatchThread());
            delegate.showAnswerState(id, answer, status);
            if (status == AnswerDisplayStatus.SAVED) {
                if ("Q01".equals(id)) savedQ1.complete(null);
                if ("Q02".equals(id)) savedQ2.complete(null);
            }
        }
        @Override public void showAnswerStatus(String id, AnswerDisplayStatus status) { delegate.showAnswerStatus(id, status); }
        @Override public void showConnectionState(ConnectionDisplayStatus status) { delegate.showConnectionState(status); }
        @Override public void setAnsweringEnabled(boolean enabled) { delegate.setAnsweringEnabled(enabled); }
        @Override public void setSubmitEnabled(boolean enabled) { delegate.setSubmitEnabled(enabled); }
        @Override public void stopCountdown() { timerStops++; delegate.stopCountdown(); }
        @Override public void showSubmissionStatus(SubmissionDisplayStatus status, String detail) {
            assertTrue(SwingUtilities.isEventDispatchThread());
            delegate.showSubmissionStatus(status, detail);
            if (status == SubmissionDisplayStatus.SUBMITTED || status == SubmissionDisplayStatus.AUTO_SUBMITTED) {
                submitted.complete(status);
            }
        }
    }
}
