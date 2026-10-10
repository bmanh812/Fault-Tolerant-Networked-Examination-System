package vn.edu.exam.client;

import org.junit.jupiter.api.Test;
import vn.edu.exam.common.protocol.Payloads;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientExamStateTest {
    @Test
    void mapsExamStateToCompleteImmutableClientSnapshot() {
        ClientExamState state = new ClientExamState();

        state.applyExamState(validExamState("EXAM-001"));

        ClientExamState.Snapshot snapshot = state.snapshot();
        assertEquals("SESSION-001", snapshot.sessionId());
        assertEquals("EXAM-001", snapshot.examId());
        assertEquals("Network Programming", snapshot.title());
        assertEquals("ACTIVE", snapshot.sessionStatus());
        assertEquals(1_790_803_600_000L, snapshot.deadline());
        assertEquals(1_790_800_000_000L, snapshot.serverTime());
        assertEquals(2, snapshot.questions().size());
        assertEquals("Q1", snapshot.questions().get(0).questionId());
        assertEquals("What does TCP provide?", snapshot.questions().get(0).text());
        assertEquals("Q1-A", snapshot.questions().get(0).options().get(0).optionId());
        assertEquals("Reliable byte stream", snapshot.questions().get(0).options().get(0).text());

        AnswerDraft answered = snapshot.answers().get("Q1");
        assertEquals(3, answered.localVersion());
        assertEquals(3, answered.confirmedVersion());
        assertEquals("Q1-A", answered.selectedOptionId());
        assertTrue(answered.isConfirmed());

        AnswerDraft unanswered = snapshot.answers().get("Q2");
        assertEquals(0, unanswered.localVersion());
        assertEquals(0, unanswered.confirmedVersion());
        assertNull(unanswered.selectedOptionId());
        assertTrue(unanswered.isConfirmed());

        assertThrows(UnsupportedOperationException.class, () -> snapshot.questions().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.answers().clear());
        assertFalse(snapshot.questions().get(0).options().isEmpty());
    }

    @Test
    void invalidStateDoesNotPartiallyReplaceExistingSnapshot() {
        ClientExamState state = new ClientExamState();
        state.applyExamState(validExamState("EXAM-001"));
        ClientExamState.Snapshot before = state.snapshot();
        Payloads.ExamState invalid = new Payloads.ExamState(
                "SESSION-NEW",
                "EXAM-002",
                "Invalid replacement",
                "ACTIVE",
                1_790_803_600_000L,
                1_790_800_000_000L,
                before.questions().stream()
                        .map(question -> new Payloads.QuestionView(
                                question.questionId(),
                                question.position(),
                                question.text(),
                                question.options().stream()
                                        .map(option -> new Payloads.OptionView(
                                                option.optionId(), option.label(), option.text()
                                        ))
                                        .toList()
                        ))
                        .toList(),
                Map.of("Q1", new Payloads.AnswerView("Q1", 4, "NOT-IN-QUESTION"))
        );

        assertThrows(IllegalArgumentException.class, () -> state.applyExamState(invalid));
        assertSame(before, state.snapshot());
        assertEquals("SESSION-001", state.sessionId());
        assertEquals("EXAM-001", state.examId());
    }

    static Payloads.ExamState validExamState(String examId) {
        Payloads.QuestionView second = new Payloads.QuestionView(
                "Q2",
                2,
                "Which layer uses TCP?",
                List.of(
                        new Payloads.OptionView("Q2-A", "A", "Application layer"),
                        new Payloads.OptionView("Q2-B", "B", "Physical layer")
                )
        );
        Payloads.QuestionView first = new Payloads.QuestionView(
                "Q1",
                1,
                "What does TCP provide?",
                List.of(
                        new Payloads.OptionView("Q1-A", "A", "Reliable byte stream"),
                        new Payloads.OptionView("Q1-B", "B", "Unreliable datagrams")
                )
        );
        return new Payloads.ExamState(
                "SESSION-001",
                examId,
                "Network Programming",
                "ACTIVE",
                1_790_803_600_000L,
                1_790_800_000_000L,
                List.of(second, first),
                Map.of("Q1", new Payloads.AnswerView("Q1", 3, "Q1-A"))
        );
    }
}
