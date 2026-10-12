package vn.edu.exam.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnswerDraftTest {
    private static final String SESSION_ID = "SESSION-001";
    private static final String REQUEST_1 = "10000000-0000-0000-0000-000000000001";
    private static final String REQUEST_2 = "10000000-0000-0000-0000-000000000002";

    @Test
    void unansweredQuestionStartsAtVersionZero() {
        AnswerDraft draft = AnswerDraft.unanswered("Q1");

        assertEquals(0, draft.localVersion());
        assertEquals(0, draft.confirmedVersion());
        assertNull(draft.selectedOptionId());
        assertTrue(draft.isSaved());
        assertEquals(AnswerDraft.SaveStatus.SAVED, draft.saveStatus());
    }

    @Test
    void firstSelectionUsesVersionOneAndRealChangeIncrementsExactlyOnce() {
        AnswerDraft first = AnswerDraft.unanswered("Q1").select("Q1-A");
        AnswerDraft changed = first.select("Q1-B");

        assertEquals(1, first.localVersion());
        assertEquals("Q1-A", first.selectedOptionId());
        assertEquals(2, changed.localVersion());
        assertEquals("Q1-B", changed.selectedOptionId());
        assertEquals(0, changed.confirmedVersion());
    }

    @Test
    void selectingCurrentOptionDoesNotChangeStateOrVersion() {
        AnswerDraft selected = AnswerDraft.unanswered("Q1").select("Q1-A");

        assertSame(selected, selected.select("Q1-A"));
        assertEquals(1, selected.localVersion());
    }

    @Test
    void updateSnapshotContainsFrozenSessionRequestQuestionVersionAndOption() {
        AnswerDraft queued = AnswerDraft.unanswered("Q1")
                .select("Q1-A")
                .prepareUpdate(SESSION_ID, REQUEST_1);

        AnswerDraft.UpdateSnapshot original = queued.pendingSnapshot();
        AnswerDraft.UpdateSnapshot retry = queued.retrySnapshot();
        AnswerDraft preparedAgain = queued.prepareUpdate(SESSION_ID, REQUEST_2);

        assertSame(original, retry);
        assertSame(queued, preparedAgain);
        assertEquals(REQUEST_1, retry.requestId());
        assertEquals(SESSION_ID, retry.sessionId());
        assertEquals("Q1", retry.questionId());
        assertEquals(1, retry.version());
        assertEquals("Q1-A", retry.selectedOptionId());
    }

    @Test
    void matchingAckAdvancesConfirmedVersionAndClearsPendingSnapshot() {
        AnswerDraft pending = AnswerDraft.unanswered("Q1")
                .select("Q1-A")
                .prepareUpdate(SESSION_ID, REQUEST_1);

        AnswerDraft acknowledged = pending.acknowledge(pending.pendingSnapshot());

        assertEquals(1, acknowledged.localVersion());
        assertEquals(1, acknowledged.confirmedVersion());
        assertNull(acknowledged.pendingSnapshot());
        assertEquals(AnswerDraft.SaveStatus.SAVED, acknowledged.saveStatus());
    }

    @Test
    void oldAckDoesNotClearOrOverwriteNewerLocalAnswer() {
        AnswerDraft versionOne = AnswerDraft.unanswered("Q1")
                .select("Q1-A")
                .prepareUpdate(SESSION_ID, REQUEST_1);
        AnswerDraft.UpdateSnapshot oldSnapshot = versionOne.pendingSnapshot();
        AnswerDraft versionTwoWaiting = versionOne
                .select("Q1-B")
                .prepareUpdate(SESSION_ID, REQUEST_2);

        assertSame(oldSnapshot, versionTwoWaiting.pendingSnapshot());
        AnswerDraft afterOldAck = versionTwoWaiting.acknowledge(oldSnapshot);

        assertEquals(2, afterOldAck.localVersion());
        assertEquals(1, afterOldAck.confirmedVersion());
        assertEquals("Q1-B", afterOldAck.selectedOptionId());
        assertNull(afterOldAck.pendingSnapshot());
        assertFalse(afterOldAck.isSaved());
        assertEquals(AnswerDraft.SaveStatus.PENDING, afterOldAck.saveStatus());

        AnswerDraft versionTwoPending = afterOldAck.prepareUpdate(SESSION_ID, REQUEST_2);
        assertEquals(REQUEST_2, versionTwoPending.pendingRequestId());
        assertEquals(2, versionTwoPending.pendingSnapshot().version());
    }

    @Test
    void duplicateAckIsIdempotent() {
        AnswerDraft pending = AnswerDraft.unanswered("Q1")
                .select("Q1-A")
                .prepareUpdate(SESSION_ID, REQUEST_1);

        AnswerDraft saved = pending.acknowledge(pending.pendingSnapshot());
        AnswerDraft duplicate = saved.acknowledge(pending.pendingSnapshot());

        assertSame(saved, duplicate);
        assertEquals(1, duplicate.confirmedVersion());
        assertNull(duplicate.pendingSnapshot());
        assertTrue(duplicate.isSaved());
    }

    @Test
    void ackBelowConfirmedVersionCannotDecreaseConfirmationOrRollbackSelection() {
        AnswerDraft versionOne = AnswerDraft.unanswered("Q1")
                .select("Q1-A")
                .prepareUpdate(SESSION_ID, REQUEST_1);
        AnswerDraft.UpdateSnapshot oldSnapshot = versionOne.pendingSnapshot();
        AnswerDraft versionTwoWaiting = versionOne.select("Q1-B");
        AnswerDraft afterVersionOneAck = versionTwoWaiting.acknowledge(oldSnapshot);
        AnswerDraft versionTwo = afterVersionOneAck.prepareUpdate(SESSION_ID, REQUEST_2);
        AnswerDraft confirmedVersionTwo = versionTwo.acknowledge(versionTwo.pendingSnapshot());

        AnswerDraft afterStaleAck = confirmedVersionTwo.acknowledge(oldSnapshot);

        assertSame(confirmedVersionTwo, afterStaleAck);
        assertEquals(2, afterStaleAck.confirmedVersion());
        assertEquals("Q1-B", afterStaleAck.selectedOptionId());
        assertTrue(afterStaleAck.isSaved());
    }

    @Test
    void savedRequiresLatestVersionToBeConfirmedWithNoNewerPendingUpdate() {
        AnswerDraft selected = AnswerDraft.unanswered("Q1").select("Q1-A");
        assertFalse(selected.isSaved());
        assertEquals(AnswerDraft.SaveStatus.PENDING, selected.saveStatus());

        AnswerDraft pending = selected.prepareUpdate(SESSION_ID, REQUEST_1);
        assertFalse(pending.isSaved());
        assertEquals(AnswerDraft.SaveStatus.SAVING, pending.saveStatus());

        AnswerDraft saved = pending.acknowledge(pending.pendingSnapshot());
        assertTrue(saved.isSaved());
        assertEquals(AnswerDraft.SaveStatus.SAVED, saved.saveStatus());

        AnswerDraft changedAgain = saved.select("Q1-B").prepareUpdate(SESSION_ID, REQUEST_2);
        assertFalse(changedAgain.isSaved());
        assertEquals(AnswerDraft.SaveStatus.SAVING, changedAgain.saveStatus());
    }
}
