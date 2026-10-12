package vn.edu.exam.client;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClientAnswerStateTest {
    private static final String REQUEST_1 = "20000000-0000-0000-0000-000000000001";

    @Test
    void answerUpdatesReplaceWholeImmutableExamSnapshot() {
        ClientExamState state = activeState();
        ClientExamState.Snapshot before = state.snapshot();

        AnswerDraft selected = state.selectAnswer("Q2", "Q2-A");
        ClientExamState.Snapshot after = state.snapshot();

        assertNotSame(before, after);
        assertEquals(0, before.answers().get("Q2").localVersion());
        assertEquals(1, selected.localVersion());
        assertEquals("Q2-A", after.answers().get("Q2").selectedOptionId());
    }

    @Test
    void rejectsAnOptionThatDoesNotBelongToQuestion() {
        ClientExamState state = activeState();

        assertThrows(
                IllegalArgumentException.class,
                () -> state.selectAnswer("Q1", "Q2-A")
        );
        assertEquals(3, state.answer("Q1").localVersion());
    }

    @Test
    void versionsAdvanceIndependentlyForEachQuestion() {
        ClientExamState state = activeState();

        state.selectAnswer("Q1", "Q1-B");
        state.selectAnswer("Q1", "Q1-A");
        state.selectAnswer("Q2", "Q2-A");

        assertEquals(5, state.answer("Q1").localVersion());
        assertEquals(1, state.answer("Q2").localVersion());
        assertEquals(3, state.answer("Q1").confirmedVersion());
        assertEquals(0, state.answer("Q2").confirmedVersion());
    }

    @Test
    void uiAndReaderThreadsCannotRaceTheSameAnswerState() throws Exception {
        ClientExamState state = activeState();
        int workerCount = 24;
        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        try {
            for (int index = 0; index < workerCount; index++) {
                results.add(workers.submit(() -> {
                    start.await();
                    state.selectAnswer("Q2", "Q2-A");
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> result : results) {
                result.get();
            }
        } finally {
            workers.shutdownNow();
        }

        AnswerDraft answer = state.answer("Q2");
        assertEquals(1, answer.localVersion());
        assertEquals("Q2-A", answer.selectedOptionId());
        assertFalse(answer.isSaved());
    }

    @Test
    void concurrentUiSelectionAndReaderAckProduceOneConsistentSnapshot() throws Exception {
        ClientExamState state = activeState();
        state.selectAnswer("Q2", "Q2-A");
        AnswerDraft.UpdateSnapshot versionOne = state.prepareAnswerUpdate("Q2", REQUEST_1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> uiChange = workers.submit(() -> {
                start.await();
                state.selectAnswer("Q2", "Q2-B");
                return null;
            });
            Future<?> readerAck = workers.submit(() -> {
                start.await();
                state.acknowledgeAnswer(versionOne);
                return null;
            });

            start.countDown();
            uiChange.get();
            readerAck.get();
        } finally {
            workers.shutdownNow();
        }

        AnswerDraft answer = state.answer("Q2");
        assertEquals(2, answer.localVersion());
        assertEquals(1, answer.confirmedVersion());
        assertEquals("Q2-B", answer.selectedOptionId());
        assertNull(answer.pendingSnapshot());
        assertEquals(AnswerDraft.SaveStatus.PENDING, answer.saveStatus());
    }

    private static ClientExamState activeState() {
        ClientExamState state = new ClientExamState();
        state.applyExamState(ClientExamStateTest.validExamState("EXAM-001"));
        return state;
    }
}
