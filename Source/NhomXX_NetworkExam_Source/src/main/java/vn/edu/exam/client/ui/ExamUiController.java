package vn.edu.exam.client.ui;

import vn.edu.exam.client.AnswerDraft;
import vn.edu.exam.client.AnswerSyncIssue;
import vn.edu.exam.client.ClientExamState;
import vn.edu.exam.client.ClientState;
import vn.edu.exam.client.ExamJoinException;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ExamUiController implements AutoCloseable {
    private final ExamClientGateway client;
    private final ExamUiView view;
    private final Executor networkExecutor;
    private final UiDispatcher ui;
    private final AtomicBoolean closed = new AtomicBoolean();

    public ExamUiController(
            ExamClientGateway client,
            ExamUiView view,
            Executor networkExecutor,
            UiDispatcher ui
    ) {
        this.client = Objects.requireNonNull(client, "client is required");
        this.view = Objects.requireNonNull(view, "view is required");
        this.networkExecutor = Objects.requireNonNull(networkExecutor, "networkExecutor is required");
        this.ui = Objects.requireNonNull(ui, "ui dispatcher is required");
    }

    public void join(String host, String portText, String candidateId, String examId) {
        JoinInput input;
        try {
            input = JoinInput.parse(host, portText, candidateId, examId);
        } catch (IllegalArgumentException error) {
            ui.dispatch(() -> view.showJoinError(error.getMessage()));
            return;
        }

        ui.dispatch(view::showJoinInProgress);
        networkExecutor.execute(() -> {
            try {
                if (client.state() == ClientState.DISCONNECTED) {
                    client.connect(input.host(), input.port());
                }
                ClientExamState.Snapshot exam = client
                        .joinExam(input.candidateId(), input.examId())
                        .get();
                ui.dispatch(() -> {
                    view.showExam(exam);
                    view.showConnectionState(ConnectionDisplayStatus.CONNECTED);
                    view.setAnsweringEnabled(true);
                });
            } catch (Throwable error) {
                String message = friendlyMessage(error);
                ui.dispatch(() -> {
                    view.showJoinError(message);
                    refreshConnectionStateOnUiThread();
                });
            }
        });
    }

    public void selectAnswer(String questionId, String optionId) {
        if (closed.get()) {
            return;
        }
        ui.dispatch(() -> view.showAnswerStatus(questionId, AnswerDisplayStatus.SAVING));

        networkExecutor.execute(() -> {
            try {
                client.updateAnswer(questionId, optionId)
                        .whenComplete((answer, error) -> ui.dispatch(() -> {
                            refreshAnswerOnUiThread(questionId);
                            if (error != null) {
                                refreshConnectionStateOnUiThread();
                            }
                        }));
                ui.dispatch(() -> refreshAnswerOnUiThread(questionId));
            } catch (RuntimeException error) {
                ui.dispatch(() -> {
                    refreshAnswerOnUiThread(questionId);
                    refreshConnectionStateOnUiThread();
                });
            }
        });
    }

    public void refreshConnectionState() {
        ui.dispatch(this::refreshConnectionStateOnUiThread);
    }

    private void refreshAnswerOnUiThread(String questionId) {
        AnswerDraft answer = client.snapshot().answers().get(questionId);
        if (answer == null) {
            return;
        }
        AnswerSyncIssue issue = client.answerSyncIssue(questionId).orElse(null);
        AnswerDisplayStatus status = AnswerDisplayStatus.from(
                answer,
                issue,
                client.answerUpdateInFlight(questionId)
        );
        view.showAnswerState(questionId, answer, status);
    }

    private void refreshConnectionStateOnUiThread() {
        ClientState state = client.state();
        ConnectionDisplayStatus display = switch (state) {
            case CONNECTING, JOINING -> ConnectionDisplayStatus.CONNECTING;
            case CONNECTED, ACTIVE -> ConnectionDisplayStatus.CONNECTED;
            default -> ConnectionDisplayStatus.DISCONNECTED;
        };
        view.showConnectionState(display);
        view.setAnsweringEnabled(state == ClientState.ACTIVE);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ui.dispatch(view::stopCountdown);
        if (networkExecutor instanceof ExecutorService service) {
            service.shutdownNow();
        }
        try {
            client.close();
        } catch (IOException ignored) {
            // Closing the window cannot recover a socket close failure.
        }
    }

    static String friendlyMessage(Throwable failure) {
        Throwable error = unwrap(failure);
        if (error instanceof ExamJoinException joinError) {
            return switch (joinError.errorCode()) {
                case CANDIDATE_NOT_ALLOWED -> "Thí sinh không được phép tham gia kỳ thi này.";
                case EXAM_NOT_STARTED -> "Kỳ thi chưa bắt đầu.";
                case EXAM_ENDED -> "Kỳ thi đã kết thúc.";
                case INVALID_REQUEST -> "Thông tin kỳ thi không hợp lệ.";
                case SERVER_BUSY -> "Máy chủ đang bận, vui lòng thử lại.";
                default -> "Không thể tham gia kỳ thi: " + joinError.getMessage();
            };
        }
        if (error instanceof TimeoutException) {
            return "Máy chủ không phản hồi trong thời gian cho phép.";
        }
        if (error instanceof IOException) {
            return "Không thể kết nối tới máy chủ.";
        }
        String detail = error.getMessage();
        return detail == null || detail.isBlank()
                ? "Không thể thực hiện yêu cầu."
                : "Không thể thực hiện yêu cầu: " + detail;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof ExecutionException || current instanceof CompletionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private record JoinInput(String host, int port, String candidateId, String examId) {
        private static JoinInput parse(
                String host,
                String portText,
                String candidateId,
                String examId
        ) {
            String checkedHost = requireText(host, "Vui lòng nhập địa chỉ máy chủ.");
            String checkedCandidate = requireText(candidateId, "Vui lòng nhập mã thí sinh.");
            String checkedExam = requireText(examId, "Vui lòng nhập mã kỳ thi.");
            int checkedPort;
            try {
                checkedPort = Integer.parseInt(requireText(portText, "Vui lòng nhập cổng máy chủ."));
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("Cổng máy chủ phải là một số nguyên hợp lệ.");
            }
            if (checkedPort < 1 || checkedPort > 65_535) {
                throw new IllegalArgumentException("Cổng máy chủ phải nằm trong khoảng 1-65535.");
            }
            return new JoinInput(checkedHost, checkedPort, checkedCandidate, checkedExam);
        }

        private static String requireText(String value, String message) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(message);
            }
            return value.trim();
        }
    }
}
