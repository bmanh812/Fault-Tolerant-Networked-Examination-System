package vn.edu.exam.server.repository;

import org.junit.jupiter.api.Test;
import vn.edu.exam.common.protocol.ErrorCode;
import vn.edu.exam.common.protocol.Payloads;
import vn.edu.exam.server.model.SessionStatus;
import vn.edu.exam.server.persistence.DatabaseManager;
import vn.edu.exam.server.service.AnswerService;
import vn.edu.exam.server.service.ServerFailure;
import vn.edu.exam.server.service.SessionService;
import vn.edu.exam.server.service.SubmissionService;
import vn.edu.exam.server.timer.DeadlineScheduler;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubmissionLifecycleTest {
    @Test
    void repeatedManualSubmitKeepsOneSubmissionAndRejectsLaterAnswers() throws Exception {
        Fixture fixture = fixture();
        try (fixture.scheduler) {
            String sessionId = fixture.sessions.joinExam("CAND-001", "EXAM-001").sessionId();
            fixture.answers.saveIfNewer(new Payloads.AnswerUpdate(sessionId, "Q01", 1, "Q01-A"));

            Payloads.SubmitAck first = fixture.submissions.submit(sessionId);
            Payloads.SubmitAck repeated = fixture.submissions.submit(sessionId);

            assertEquals(first, repeated);
            assertEquals("SUBMITTED", first.status());
            assertEquals(SessionStatus.SUBMITTED, fixture.repository.findById(sessionId).orElseThrow().status());
            assertEquals(1, submissionCount(fixture.database, sessionId));
            assertEquals("MANUAL", submitType(fixture.database, sessionId));
            ServerFailure rejected = assertThrows(ServerFailure.class, () -> fixture.answers.saveIfNewer(
                    new Payloads.AnswerUpdate(sessionId, "Q01", 2, "Q01-B")
            ));
            assertEquals(ErrorCode.ALREADY_SUBMITTED, rejected.code());
            assertEquals(1, fixture.repository.findAnswers(sessionId).get("Q01").version());
        }
    }

    @Test
    void offlineSessionIsAutoSubmittedBySweepAndOnlyOnce() throws Exception {
        Fixture fixture = fixture();
        try (fixture.scheduler) {
            String sessionId = fixture.sessions.joinExam("CAND-001", "EXAM-001").sessionId();
            long deadline = fixture.clock.millis() + 1000;
            setDeadline(fixture.database, sessionId, deadline);
            fixture.clock.set(deadline + 1);

            fixture.submissions.startSweep(Duration.ofSeconds(1));
            fixture.submissions.sweepExpired();

            assertEquals(SessionStatus.AUTO_SUBMITTED, fixture.repository.findById(sessionId).orElseThrow().status());
            assertEquals(1, submissionCount(fixture.database, sessionId));
            assertEquals("AUTO", submitType(fixture.database, sessionId));
            assertEquals(1, fixture.events.size());
            assertEquals("AUTO_SUBMITTED", fixture.events.get(0).status());
            assertEquals(sessionId, fixture.events.get(0).sessionId());
            assertEquals(ErrorCode.ALREADY_SUBMITTED, assertThrows(ServerFailure.class,
                    () -> fixture.submissions.submit(sessionId)).code());
        }
    }

    @Test
    void answerWaitsForSessionLockAndCannotUseTimeCapturedBeforeDeadline() throws Exception {
        Fixture fixture = fixture();
        try (fixture.scheduler) {
            String sessionId = fixture.sessions.joinExam("CAND-001", "EXAM-001").sessionId();
            long deadline = fixture.clock.millis() + 1000;
            setDeadline(fixture.database, sessionId, deadline);

            try (Connection blocker = fixture.database.open()) {
                blocker.setAutoCommit(false);
                try (PreparedStatement lock = blocker.prepareStatement(
                        "SELECT session_id FROM exam_session WHERE session_id = ? FOR UPDATE"
                )) {
                    lock.setString(1, sessionId);
                    try (ResultSet ignored = lock.executeQuery()) {
                        assertTrue(ignored.next());
                    }
                }

                CountDownLatch attempting = new CountDownLatch(1);
                CompletableFuture<ErrorCode> delayed = CompletableFuture.supplyAsync(() -> {
                    attempting.countDown();
                    try {
                        fixture.answers.saveIfNewer(new Payloads.AnswerUpdate(sessionId, "Q01", 1, "Q01-A"));
                        return null;
                    } catch (ServerFailure failure) {
                        return failure.code();
                    }
                });
                assertTrue(attempting.await(2, TimeUnit.SECONDS));
                Thread.sleep(100);
                assertFalse(delayed.isDone());
                fixture.clock.set(deadline + 1);
                blocker.commit();

                assertEquals(ErrorCode.EXAM_ENDED, delayed.get(3, TimeUnit.SECONDS));
                assertTrue(fixture.repository.findAnswers(sessionId).isEmpty());
            }
        }
    }

    @Test
    void submitAfterDeadlineAutoFinalizesBeforeReturningError() throws Exception {
        Fixture fixture = fixture();
        try (fixture.scheduler) {
            String sessionId = fixture.sessions.joinExam("CAND-001", "EXAM-001").sessionId();
            long deadline = fixture.clock.millis() + 1000;
            setDeadline(fixture.database, sessionId, deadline);
            fixture.clock.set(deadline);

            ServerFailure error = assertThrows(ServerFailure.class, () -> fixture.submissions.submit(sessionId));
            assertEquals(ErrorCode.EXAM_ENDED, error.code());
            assertEquals(SessionStatus.AUTO_SUBMITTED, fixture.repository.findById(sessionId).orElseThrow().status());
            assertEquals(1, submissionCount(fixture.database, sessionId));
            assertEquals(1, fixture.events.size());
        }
    }

    @Test
    void concurrentAnswerAndSubmitCannotWriteAfterFinalization() throws Exception {
        Fixture fixture = fixture();
        try (fixture.scheduler) {
            String sessionId = fixture.sessions.joinExam("CAND-001", "EXAM-001").sessionId();
            try (Connection blocker = fixture.database.open()) {
                blocker.setAutoCommit(false);
                try (PreparedStatement lock = blocker.prepareStatement(
                        "SELECT session_id FROM exam_session WHERE session_id = ? FOR UPDATE"
                )) {
                    lock.setString(1, sessionId);
                    try (ResultSet ignored = lock.executeQuery()) {
                        assertTrue(ignored.next());
                    }
                }

                CountDownLatch attempting = new CountDownLatch(2);
                CompletableFuture<ErrorCode> answer = CompletableFuture.supplyAsync(() -> {
                    attempting.countDown();
                    try {
                        fixture.answers.saveIfNewer(new Payloads.AnswerUpdate(sessionId, "Q01", 1, "Q01-A"));
                        return null;
                    } catch (ServerFailure failure) {
                        return failure.code();
                    }
                });
                CompletableFuture<Payloads.SubmitAck> submit = CompletableFuture.supplyAsync(() -> {
                    attempting.countDown();
                    return fixture.submissions.submit(sessionId);
                });
                assertTrue(attempting.await(2, TimeUnit.SECONDS));
                blocker.commit();

                assertEquals("SUBMITTED", submit.get(3, TimeUnit.SECONDS).status());
                ErrorCode answerResult = answer.get(3, TimeUnit.SECONDS);
                if (answerResult != null) {
                    assertEquals(ErrorCode.ALREADY_SUBMITTED, answerResult);
                    assertTrue(fixture.repository.findAnswers(sessionId).isEmpty());
                } else {
                    assertEquals(1, fixture.repository.findAnswers(sessionId).get("Q01").version());
                }
                assertEquals(1, submissionCount(fixture.database, sessionId));
                assertEquals(ErrorCode.ALREADY_SUBMITTED, assertThrows(ServerFailure.class,
                        () -> fixture.answers.saveIfNewer(
                                new Payloads.AnswerUpdate(sessionId, "Q01", 2, "Q01-B")
                        )).code());
            }
        }
    }

    private static Fixture fixture() throws Exception {
        MutableClock clock = new MutableClock(System.currentTimeMillis());
        DatabaseManager database = new DatabaseManager(
                "jdbc:h2:mem:submit_" + UUID.randomUUID().toString().replace('-', '_') + ";DB_CLOSE_DELAY=-1",
                "sa", ""
        );
        database.initialize(Path.of("data", "schema.sql"));
        database.seed(Path.of("data", "sample-data.sql"));
        H2SessionRepository repository = new H2SessionRepository(database, clock);
        DeadlineScheduler scheduler = new DeadlineScheduler();
        List<Payloads.AutoSubmit> events = new ArrayList<>();
        return new Fixture(database, clock, repository, scheduler,
                new SessionService(repository, clock), new AnswerService(repository, clock),
                new SubmissionService(repository, scheduler, events::add), events);
    }

    private static void setDeadline(DatabaseManager database, String sessionId, long deadline) throws Exception {
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE exam_session SET deadline = ? WHERE session_id = ?"
             )) {
            statement.setLong(1, deadline);
            statement.setString(2, sessionId);
            statement.executeUpdate();
        }
    }

    private static int submissionCount(DatabaseManager database, String sessionId) throws Exception {
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM submission WHERE session_id = ?"
             )) {
            statement.setString(1, sessionId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static String submitType(DatabaseManager database, String sessionId) throws Exception {
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT submit_type FROM submission WHERE session_id = ?"
             )) {
            statement.setString(1, sessionId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }

    private record Fixture(
            DatabaseManager database,
            MutableClock clock,
            H2SessionRepository repository,
            DeadlineScheduler scheduler,
            SessionService sessions,
            AnswerService answers,
            SubmissionService submissions,
            List<Payloads.AutoSubmit> events
    ) {
    }

    private static final class MutableClock extends Clock {
        private final AtomicLong time;

        private MutableClock(long initial) {
            time = new AtomicLong(initial);
        }

        void set(long value) {
            time.set(value);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(time.get());
        }

        @Override
        public long millis() {
            return time.get();
        }
    }
}
