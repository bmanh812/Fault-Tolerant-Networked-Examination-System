package vn.edu.exam.client.ui;

import org.junit.jupiter.api.Test;
import vn.edu.exam.client.AnswerDraft;
import vn.edu.exam.client.AnswerSyncIssue;
import vn.edu.exam.client.ClientExamState;
import vn.edu.exam.client.ClientState;
import vn.edu.exam.client.ExamJoinException;
import vn.edu.exam.common.protocol.ErrorCode;
import vn.edu.exam.common.protocol.Payloads;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExamUiControllerTest {
    @Test
    void joinSuccessMovesViewToExamWithoutRunningNetworkWorkInline() {
        FakeGateway gateway = new FakeGateway(snapshot(false));
        FakeView view = new FakeView();
        QueuedExecutor network = new QueuedExecutor();
        ExamUiController controller = controller(gateway, view, network);

        controller.join("127.0.0.1", "9090", "CAND-001", "EXAM-001");

        assertTrue(view.joinInProgress);
        assertNull(view.exam);
        assertFalse(gateway.connectCalled);

        network.runNext();

        assertTrue(gateway.connectCalled);
        assertEquals("CAND-001", gateway.candidateId);
        assertEquals("EXAM-001", gateway.examId);
        assertEquals("SESSION-001", view.exam.sessionId());
        assertEquals(ConnectionDisplayStatus.CONNECTED, view.connectionStatus);
        assertTrue(view.answeringEnabled);
    }

    @Test
    void joinErrorIsFriendlyAndDoesNotCrashTheController() {
        FakeGateway gateway = new FakeGateway(snapshot(false));
        gateway.joinFailure = new ExamJoinException(
                ErrorCode.CANDIDATE_NOT_ALLOWED,
                "Candidate rejected"
        );
        FakeView view = new FakeView();
        ExamUiController controller = controller(gateway, view, Runnable::run);

        controller.join("127.0.0.1", "9090", "BAD", "EXAM-001");

        assertTrue(view.joinError.contains("không được phép"));
        assertNull(view.exam);
        assertEquals(ConnectionDisplayStatus.CONNECTED, view.connectionStatus);
        assertFalse(view.answeringEnabled);
    }

    @Test
    void restoredAnswerFromExamStateRemainsThePresentationSourceOfTruth() {
        FakeGateway gateway = new FakeGateway(snapshot(true));
        FakeView view = new FakeView();
        ExamUiController controller = controller(gateway, view, Runnable::run);

        controller.join("127.0.0.1", "9090", "CAND-001", "EXAM-001");

        AnswerDraft restored = view.exam.answers().get("Q1");
        assertEquals("Q1-B", restored.selectedOptionId());
        assertEquals(2, restored.localVersion());
        assertEquals(2, restored.confirmedVersion());
        assertTrue(restored.isSaved());
    }

    @Test
    void selectingOptionCallsCoreApiAndRefreshesSavingThenSavedState() {
        FakeGateway gateway = new FakeGateway(snapshot(false));
        gateway.state = ClientState.ACTIVE;
        FakeView view = new FakeView();
        ExamUiController controller = controller(gateway, view, Runnable::run);

        controller.selectAnswer("Q1", "Q1-A");

        assertEquals(1, gateway.updateCalls);
        assertEquals("Q1", gateway.updatedQuestionId);
        assertEquals("Q1-A", gateway.updatedOptionId);
        assertEquals(AnswerDisplayStatus.SAVING, view.answerStatuses.get("Q1"));

        gateway.completeAnswerUpdate();

        assertEquals(AnswerDisplayStatus.SAVED, view.answerStatuses.get("Q1"));
        assertEquals("Q1-A", view.answers.get("Q1").selectedOptionId());
        assertTrue(view.answers.get("Q1").isSaved());
    }

    @Test
    void mapsEveryRequiredSaveStatusFromClientCoreState() {
        AnswerDraft unanswered = AnswerDraft.unanswered("Q1");
        AnswerDraft pending = unanswered.select("Q1-A")
                .prepareUpdate("SESSION-001", "10000000-0000-0000-0000-000000000001");
        AnswerDraft saved = pending.acknowledge(pending.pendingSnapshot());
        AnswerSyncIssue issue = new AnswerSyncIssue(
                ErrorCode.VERSION_CONFLICT,
                "Q1",
                "Conflict",
                new Payloads.AnswerView("Q1", 1, "Q1-B")
        );

        assertEquals(AnswerDisplayStatus.UNANSWERED, AnswerDisplayStatus.from(unanswered, null, false));
        assertEquals(AnswerDisplayStatus.SAVING, AnswerDisplayStatus.from(pending, null, true));
        assertEquals(AnswerDisplayStatus.SAVED, AnswerDisplayStatus.from(saved, null, false));
        assertEquals(AnswerDisplayStatus.SYNC_ERROR, AnswerDisplayStatus.from(pending, issue, false));
        assertEquals(AnswerDisplayStatus.SYNC_ERROR, AnswerDisplayStatus.from(pending, null, false));
    }

    @Test
    void connectionFailureDisablesAnsweringWithoutAttemptingReconnect() {
        FakeGateway gateway = new FakeGateway(snapshot(false));
        gateway.state = ClientState.DISCONNECTED;
        FakeView view = new FakeView();
        ExamUiController controller = controller(gateway, view, Runnable::run);

        controller.refreshConnectionState();

        assertEquals(ConnectionDisplayStatus.DISCONNECTED, view.connectionStatus);
        assertFalse(view.answeringEnabled);
        assertEquals(0, gateway.connectAttemptsAfterConstruction);
    }

    @Test
    void rapidSelectionsAreQueuedWithoutCallingNetworkCoreInline() {
        FakeGateway gateway = new FakeGateway(snapshot(false));
        gateway.state = ClientState.ACTIVE;
        FakeView view = new FakeView();
        QueuedExecutor network = new QueuedExecutor();
        ExamUiController controller = controller(gateway, view, network);

        controller.selectAnswer("Q1", "Q1-A");
        controller.selectAnswer("Q1", "Q1-B");
        controller.selectAnswer("Q1", "Q1-A");

        assertEquals(0, gateway.updateCalls);
        assertEquals(3, network.size());
        assertEquals(AnswerDisplayStatus.SAVING, view.answerStatuses.get("Q1"));
    }

    @Test
    void closeIsIdempotentAndClosesTheClientGateway() {
        FakeGateway gateway = new FakeGateway(snapshot(false));
        ExamUiController controller = controller(gateway, new FakeView(), Runnable::run);

        controller.close();
        controller.close();

        assertEquals(1, gateway.closeCalls);
    }

    private static ExamUiController controller(
            FakeGateway gateway,
            FakeView view,
            Executor executor
    ) {
        return new ExamUiController(gateway, view, executor, Runnable::run);
    }

    private static ClientExamState snapshot(boolean withSavedAnswer) {
        ClientExamState state = new ClientExamState();
        Map<String, Payloads.AnswerView> answers = withSavedAnswer
                ? Map.of("Q1", new Payloads.AnswerView("Q1", 2, "Q1-B"))
                : Map.of();
        state.applyExamState(new Payloads.ExamState(
                "SESSION-001",
                "EXAM-001",
                "Network Programming",
                "ACTIVE",
                1_790_803_600_000L,
                1_790_800_000_000L,
                List.of(new Payloads.QuestionView(
                        "Q1",
                        1,
                        "TCP provides which service?",
                        List.of(
                                new Payloads.OptionView("Q1-A", "A", "Reliable byte stream"),
                                new Payloads.OptionView("Q1-B", "B", "Unreliable datagrams")
                        )
                )),
                answers
        ));
        return state;
    }

    private static final class FakeGateway implements ExamClientGateway {
        private final ClientExamState examState;
        private ClientState state = ClientState.DISCONNECTED;
        private Throwable joinFailure;
        private CompletableFuture<AnswerDraft> answerFuture;
        private AnswerDraft.UpdateSnapshot pendingAnswer;
        private boolean inFlight;
        private boolean connectCalled;
        private int connectAttemptsAfterConstruction;
        private int updateCalls;
        private int closeCalls;
        private String candidateId;
        private String examId;
        private String updatedQuestionId;
        private String updatedOptionId;

        private FakeGateway(ClientExamState examState) {
            this.examState = examState;
        }

        @Override
        public void connect(String host, int port) {
            connectCalled = true;
            connectAttemptsAfterConstruction++;
            state = ClientState.CONNECTED;
        }

        @Override
        public CompletableFuture<ClientExamState.Snapshot> joinExam(String candidateId, String examId) {
            this.candidateId = candidateId;
            this.examId = examId;
            if (joinFailure != null) {
                return CompletableFuture.failedFuture(joinFailure);
            }
            state = ClientState.ACTIVE;
            return CompletableFuture.completedFuture(examState.snapshot());
        }

        @Override
        public CompletableFuture<AnswerDraft> updateAnswer(String questionId, String optionId) {
            updateCalls++;
            updatedQuestionId = questionId;
            updatedOptionId = optionId;
            examState.selectAnswer(questionId, optionId);
            pendingAnswer = examState.prepareAnswerUpdate(questionId);
            inFlight = true;
            answerFuture = new CompletableFuture<>();
            return answerFuture;
        }

        private void completeAnswerUpdate() {
            AnswerDraft saved = examState.acknowledgeAnswer(pendingAnswer);
            inFlight = false;
            answerFuture.complete(saved);
        }

        @Override
        public ClientExamState.Snapshot snapshot() {
            return examState.snapshot();
        }

        @Override
        public ClientState state() {
            return state;
        }

        @Override
        public Optional<AnswerSyncIssue> answerSyncIssue(String questionId) {
            return Optional.empty();
        }

        @Override
        public boolean answerUpdateInFlight(String questionId) {
            return inFlight;
        }

        @Override
        public void close() throws IOException {
            closeCalls++;
            state = ClientState.CLOSED;
        }
    }

    private static final class FakeView implements ExamUiView {
        private boolean joinInProgress;
        private boolean answeringEnabled;
        private String joinError;
        private ClientExamState.Snapshot exam;
        private ConnectionDisplayStatus connectionStatus;
        private final Map<String, AnswerDisplayStatus> answerStatuses = new java.util.HashMap<>();
        private final Map<String, AnswerDraft> answers = new java.util.HashMap<>();

        @Override
        public void showJoinInProgress() {
            joinInProgress = true;
        }

        @Override
        public void showJoinError(String message) {
            joinError = message;
        }

        @Override
        public void showExam(ClientExamState.Snapshot exam) {
            this.exam = exam;
        }

        @Override
        public void showAnswerState(String questionId, AnswerDraft answer, AnswerDisplayStatus status) {
            answers.put(questionId, answer);
            answerStatuses.put(questionId, status);
        }

        @Override
        public void showAnswerStatus(String questionId, AnswerDisplayStatus status) {
            answerStatuses.put(questionId, status);
        }

        @Override
        public void showConnectionState(ConnectionDisplayStatus status) {
            connectionStatus = status;
        }

        @Override
        public void setAnsweringEnabled(boolean enabled) {
            answeringEnabled = enabled;
        }
    }

    private static final class QueuedExecutor implements Executor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private void runNext() {
            tasks.remove(0).run();
        }

        private int size() {
            return tasks.size();
        }
    }
}
