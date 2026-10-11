package vn.edu.exam.server.repository;

import org.junit.jupiter.api.Test;
import vn.edu.exam.common.protocol.ErrorCode;
import vn.edu.exam.common.protocol.Payloads;
import vn.edu.exam.server.persistence.DatabaseManager;
import vn.edu.exam.server.service.AnswerService;
import vn.edu.exam.server.service.ServerFailure;
import vn.edu.exam.server.service.SessionService;

import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H2SessionRepositoryTest {
    @Test
    void joinCreatesOneSessionPerCandidateAndReturnsMcqWithoutCorrectAnswers() throws Exception {
        Fixture fixture = fixture();

        Payloads.ExamState first = fixture.sessions.joinExam("CAND-001", "EXAM-001");
        Payloads.ExamState repeated = fixture.sessions.joinExam("CAND-001", "EXAM-001");
        Payloads.ExamState secondCandidate = fixture.sessions.joinExam("CAND-002", "EXAM-001");

        assertEquals(first.sessionId(), repeated.sessionId());
        assertNotEquals(first.sessionId(), secondCandidate.sessionId());
        assertEquals("ACTIVE", first.status());
        assertEquals(3, first.questions().size());
        assertEquals("Q01", first.questions().get(0).questionId());
        assertEquals("Q01-A", first.questions().get(0).options().get(0).optionId());
        assertTrue(first.answers().isEmpty());
        assertTrue(first.deadline() > first.serverTime());
        assertFalse(first.questions().get(0).toString().contains("isCorrect"));
    }

    @Test
    void commitsAnswerBeforeAckAndProtectsNewerVersion() throws Exception {
        Fixture fixture = fixture();
        String sessionId = fixture.sessions.joinExam("CAND-001", "EXAM-001").sessionId();

        Payloads.AnswerAck first = fixture.answers.saveIfNewer(
                new Payloads.AnswerUpdate(sessionId, "Q01", 1, "Q01-A")
        );
        assertEquals(1, first.confirmedVersion());
        assertEquals("Q01-A", fixture.repository.findAnswers(sessionId).get("Q01").selectedOptionId());

        Payloads.AnswerAck duplicate = fixture.answers.saveIfNewer(
                new Payloads.AnswerUpdate(sessionId, "Q01", 1, "Q01-A")
        );
        assertEquals(first.confirmedVersion(), duplicate.confirmedVersion());

        fixture.answers.saveIfNewer(new Payloads.AnswerUpdate(sessionId, "Q01", 2, "Q01-B"));
        ServerFailure stale = assertThrows(ServerFailure.class, () -> fixture.answers.saveIfNewer(
                new Payloads.AnswerUpdate(sessionId, "Q01", 1, "Q01-A")
        ));
        assertEquals(ErrorCode.VERSION_CONFLICT, stale.code());
        assertEquals(2, stale.currentAnswer().version());
        assertEquals("Q01-B", stale.currentAnswer().selectedOptionId());

        ServerFailure conflicting = assertThrows(ServerFailure.class, () -> fixture.answers.saveIfNewer(
                new Payloads.AnswerUpdate(sessionId, "Q01", 2, "Q01-C")
        ));
        assertEquals(ErrorCode.VERSION_CONFLICT, conflicting.code());
        assertEquals("Q01-B", fixture.repository.findAnswers(sessionId).get("Q01").selectedOptionId());

        Payloads.ExamState rejoined = fixture.sessions.joinExam("CAND-001", "EXAM-001");
        assertEquals(sessionId, rejoined.sessionId());
        assertEquals(2, rejoined.answers().get("Q01").version());
        assertEquals("Q01-B", rejoined.answers().get("Q01").selectedOptionId());
    }

    @Test
    void rejectsAnswersThatDoNotBelongToTheSessionExam() throws Exception {
        Fixture fixture = fixture();
        String sessionId = fixture.sessions.joinExam("CAND-001", "EXAM-001").sessionId();

        ServerFailure wrongQuestion = assertThrows(ServerFailure.class, () -> fixture.answers.saveIfNewer(
                new Payloads.AnswerUpdate(sessionId, "MISSING", 1, "Q01-A")
        ));
        assertEquals(ErrorCode.QUESTION_NOT_FOUND, wrongQuestion.code());

        ServerFailure wrongOption = assertThrows(ServerFailure.class, () -> fixture.answers.saveIfNewer(
                new Payloads.AnswerUpdate(sessionId, "Q01", 1, "Q02-A")
        ));
        assertEquals(ErrorCode.INVALID_OPTION, wrongOption.code());
        assertTrue(fixture.repository.findAnswers(sessionId).isEmpty());
    }

    private static Fixture fixture() throws Exception {
        DatabaseManager database = new DatabaseManager(
                "jdbc:h2:mem:server_" + UUID.randomUUID().toString().replace('-', '_') + ";DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        database.initialize(Path.of("data", "schema.sql"));
        database.seed(Path.of("data", "sample-data.sql"));
        H2SessionRepository repository = new H2SessionRepository(database);
        Clock clock = Clock.systemUTC();
        return new Fixture(
                repository,
                new SessionService(repository, clock),
                new AnswerService(repository, clock)
        );
    }

    private record Fixture(
            H2SessionRepository repository,
            SessionService sessions,
            AnswerService answers
    ) {
    }
}
