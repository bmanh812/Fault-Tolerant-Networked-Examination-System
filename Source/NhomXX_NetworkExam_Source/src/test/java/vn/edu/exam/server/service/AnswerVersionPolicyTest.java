package vn.edu.exam.server.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static vn.edu.exam.server.service.AnswerVersionPolicy.Decision.APPLY;
import static vn.edu.exam.server.service.AnswerVersionPolicy.Decision.CONFLICT;
import static vn.edu.exam.server.service.AnswerVersionPolicy.Decision.DUPLICATE;
import static vn.edu.exam.server.service.AnswerVersionPolicy.Decision.STALE;

class AnswerVersionPolicyTest {
    @Test
    void appliesNewerVersion() {
        assertEquals(APPLY, AnswerVersionPolicy.evaluate(4, "Q12-B", 5, "Q12-C"));
    }

    @Test
    void acceptsExactDuplicateWithoutApplyingAgain() {
        assertEquals(DUPLICATE, AnswerVersionPolicy.evaluate(5, "Q12-C", 5, "Q12-C"));
    }

    @Test
    void rejectsOlderVersion() {
        assertEquals(STALE, AnswerVersionPolicy.evaluate(5, "Q12-C", 4, "Q12-B"));
    }

    @Test
    void detectsSameVersionWithDifferentPayload() {
        assertEquals(CONFLICT, AnswerVersionPolicy.evaluate(5, "Q12-C", 5, "Q12-D"));
    }
}
