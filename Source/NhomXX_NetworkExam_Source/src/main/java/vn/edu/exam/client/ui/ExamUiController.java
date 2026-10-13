package vn.edu.exam.client.ui;

import vn.edu.exam.client.AnswerDraft;
import vn.edu.exam.client.AnswerSyncIssue;
import vn.edu.exam.client.ClientExamState;
import vn.edu.exam.client.ClientState;
import vn.edu.exam.client.ExamJoinException;
import vn.edu.exam.client.ExamSubmission;
import vn.edu.exam.client.SubmitException;

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
    private final AtomicBoolean submissionInProgress = new AtomicBoolean();
    /** Accessed only through the UI dispatcher. */
    private ExamSubmission displayedSubmission;

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
        this.client.setSubmissionHandler(submission ->
                this.ui.dispatch(() -> showFinalSubmissionOnUiThread(submission))
        );
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
                    // A deadline push may have arrived before this queued JOIN presentation.
                    if (displayedSubmission != null) {
                        renderFinalSubmission(displayedSubmission);
                    } else {
                        view.showSubmissionStatus(SubmissionDisplayStatus.READY, null);
                    }
                    refreshConnectionStateOnUiThread();
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
        try {
            // Apply the local choice before queuing I/O, so immediate submit includes this choice.
            client.selectAnswer(questionId, optionId);
        } catch (RuntimeException error) {
            ui.dispatch(() -> refreshAnswerOnUiThread(questionId));
            return;
        }
        ui.dispatch(() -> view.showAnswerStatus(questionId, AnswerDisplayStatus.SAVING));

        networkExecutor.execute(() -> {
            try {
                if (client.state() != ClientState.ACTIVE) {
                    return; // The submission flush owns all staged answers after freeze.
                }
                client.sendAnswerUpdate(questionId)
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

    public void submit() {
        if (closed.get() || !submissionInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            client.beginSubmission();
        } catch (RuntimeException error) {
            submissionInProgress.set(false);
            ui.dispatch(() -> showSubmitFailure(error));
            return;
        }
        ui.dispatch(() -> {
            if (displayedSubmission != null || closed.get()) {
                return;
            }
            view.setAnsweringEnabled(false);
            view.setSubmitEnabled(false);
            boolean pending = client.snapshot().answers().values().stream().anyMatch(answer -> !answer.isSaved());
            view.showSubmissionStatus(pending && !client.submitRequestSent()
                    ? SubmissionDisplayStatus.SYNCHRONIZING
                    : SubmissionDisplayStatus.SUBMITTING, null);
        });

        networkExecutor.execute(() -> {
            try {
                client.submitExam().whenComplete((submission, error) -> {
                    submissionInProgress.set(false);
                    ui.dispatch(() -> {
                        if (error == null) {
                            showFinalSubmissionOnUiThread(submission);
                        } else {
                            showSubmitFailure(error);
                        }
                    });
                });
            } catch (RuntimeException error) {
                submissionInProgress.set(false);
                ui.dispatch(() -> showSubmitFailure(error));
            }
        });
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
            default -> client.isConnected()
                    ? ConnectionDisplayStatus.CONNECTED : ConnectionDisplayStatus.DISCONNECTED;
        };
        view.showConnectionState(display);
        view.setAnsweringEnabled(state == ClientState.ACTIVE && !submissionInProgress.get());
        view.setSubmitEnabled(
                client.isConnected() && !submissionInProgress.get()
                        && (state == ClientState.ACTIVE
                        || (state == ClientState.SUBMITTING && client.submitRetryAvailable()))
        );
        if (state == ClientState.SUBMITTING && submissionInProgress.get()) {
            view.showSubmissionStatus(client.submitRequestSent()
                    ? SubmissionDisplayStatus.SUBMITTING
                    : SubmissionDisplayStatus.SYNCHRONIZING, null);
        }
    }

    private void showFinalSubmissionOnUiThread(ExamSubmission submission) {
        if (closed.get() || submission == null || displayedSubmission != null) {
            return;
        }
        displayedSubmission = submission;
        renderFinalSubmission(submission);
    }

    private void renderFinalSubmission(ExamSubmission submission) {
        view.setAnsweringEnabled(false);
        view.setSubmitEnabled(false);
        view.stopCountdown();
        view.showSubmissionStatus(
                submission.autoSubmitted()
                        ? SubmissionDisplayStatus.AUTO_SUBMITTED
                        : SubmissionDisplayStatus.SUBMITTED,
                null
        );
    }

    private void showSubmitFailure(Throwable error) {
        if (closed.get() || displayedSubmission != null) {
            return;
        }
        view.showSubmissionStatus(SubmissionDisplayStatus.RETRYABLE_ERROR, friendlySubmitMessage(error));
        refreshConnectionStateOnUiThread();
    }

    private static String friendlySubmitMessage(Throwable failure) {
        Throwable error = unwrap(failure);
        if (error instanceof SubmitException submitError) {
            if (submitError.answerSyncFailure()) {
                return "Không thể đồng bộ toàn bộ đáp án trước khi nộp. Vui lòng thử lại.";
            }
            if (submitError.errorCode() != null) {
                return switch (submitError.errorCode()) {
                    case EXAM_ENDED, ALREADY_SUBMITTED ->
                            "Server đang xác nhận trạng thái nộp bài. Vui lòng chờ hoặc thử lại.";
                    case SERVER_BUSY -> "Máy chủ đang bận, vui lòng thử nộp lại.";
                    default -> "Server từ chối nộp bài: " + submitError.getMessage();
                };
            }
        }
        if (error instanceof TimeoutException) {
            return "Chưa nhận được xác nhận từ Server. Thử lại sẽ giữ nguyên yêu cầu nộp bài.";
        }
        if (error instanceof IOException) {
            return "Mất kết nối khi nộp bài; kết quả chưa được xác nhận.";
        }
        return "Chưa thể nộp bài: " + (error.getMessage() == null
                ? "lỗi không xác định"
                : error.getMessage());
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
