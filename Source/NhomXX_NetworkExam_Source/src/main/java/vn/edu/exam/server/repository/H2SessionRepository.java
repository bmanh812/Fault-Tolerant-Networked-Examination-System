package vn.edu.exam.server.repository;

import vn.edu.exam.common.protocol.ErrorCode;
import vn.edu.exam.common.protocol.Payloads;
import vn.edu.exam.server.model.AnswerState;
import vn.edu.exam.server.model.ExamSession;
import vn.edu.exam.server.model.SessionStatus;
import vn.edu.exam.server.persistence.DatabaseManager;
import vn.edu.exam.server.service.AnswerVersionPolicy;
import vn.edu.exam.server.service.ServerFailure;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class H2SessionRepository implements SessionRepository {
    private final DatabaseManager database;
    private final Clock clock;

    public H2SessionRepository(DatabaseManager database) {
        this(database, Clock.systemUTC());
    }

    public H2SessionRepository(DatabaseManager database, Clock clock) {
        this.database = Objects.requireNonNull(database, "database is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    @Override
    public JoinedExam joinExam(String candidateId, String examId) {
        return transact(connection -> {
            requireCandidate(connection, candidateId);
            long now = clock.millis();
            ExamInfo exam = loadActiveExam(connection, examId, now);
            ExamSession session = findByCandidateAndExam(connection, candidateId, examId);
            if (session == null) {
                session = new ExamSession(
                        UUID.randomUUID().toString(),
                        examId,
                        candidateId,
                        SessionStatus.ACTIVE,
                        now,
                        exam.endTime()
                );
                insertSession(connection, session);
            } else if (session.status() != SessionStatus.ACTIVE) {
                throw new ServerFailure(ErrorCode.ALREADY_SUBMITTED, "This exam session is already finished");
            } else if (now >= session.deadline()) {
                throw new ServerFailure(ErrorCode.EXAM_ENDED, "The exam deadline has passed");
            }

            List<Payloads.QuestionView> questions = loadQuestions(connection, examId);
            if (questions.isEmpty()) {
                throw new ServerFailure(ErrorCode.INTERNAL_ERROR, "The exam has no questions");
            }
            return new JoinedExam(session, exam.title(), questions, loadAnswers(connection, session.sessionId()));
        });
    }

    @Override
    public Optional<ExamSession> findById(String sessionId) {
        try (Connection connection = database.open()) {
            return Optional.ofNullable(loadSession(connection, sessionId, false));
        } catch (SQLException error) {
            throw databaseFailure(error);
        }
    }

    @Override
    public Map<String, AnswerState> findAnswers(String sessionId) {
        try (Connection connection = database.open()) {
            return loadAnswers(connection, sessionId);
        } catch (SQLException error) {
            throw databaseFailure(error);
        }
    }

    @Override
    public AnswerState saveAnswerIfNewer(Payloads.AnswerUpdate incoming) {
        Objects.requireNonNull(incoming, "answer update is required");
        return transact(connection -> {
            ExamSession session = loadSession(connection, incoming.sessionId(), true);
            if (session == null) {
                throw new ServerFailure(ErrorCode.INVALID_SESSION, "Exam session was not found");
            }
            if (session.status() != SessionStatus.ACTIVE) {
                throw new ServerFailure(ErrorCode.ALREADY_SUBMITTED, "This exam session is already finished");
            }
            long now = clock.millis();
            if (now >= session.deadline()) {
                throw new ServerFailure(ErrorCode.EXAM_ENDED, "The exam deadline has passed");
            }
            AnswerState stamped = new AnswerState(
                    incoming.sessionId(), incoming.questionId(), incoming.version(), incoming.selectedOptionId(), now
            );
            requireQuestionAndOption(connection, session.examId(), incoming.questionId(), incoming.selectedOptionId());

            AnswerState current = loadAnswer(connection, incoming.sessionId(), incoming.questionId());
            if (current == null) {
                insertAnswer(connection, stamped);
                return stamped;
            }
            AnswerVersionPolicy.Decision decision = AnswerVersionPolicy.evaluate(
                    current.version(), current.selectedOptionId(),
                    incoming.version(), incoming.selectedOptionId()
            );
            return switch (decision) {
                case APPLY -> {
                    updateAnswer(connection, stamped);
                    yield stamped;
                }
                case DUPLICATE -> current;
                case STALE, CONFLICT -> throw new ServerFailure(
                        ErrorCode.VERSION_CONFLICT,
                        "Answer version conflicts with the authoritative state",
                        new Payloads.AnswerView(
                                current.questionId(), current.version(), current.selectedOptionId()
                        )
                );
            };
        });
    }

    @Override
    public FinalizedSession submit(String sessionId) {
        return transact(connection -> {
            ExamSession session = loadSession(connection, sessionId, true);
            if (session == null) {
                throw new ServerFailure(ErrorCode.INVALID_SESSION, "Exam session was not found");
            }
            if (session.status() != SessionStatus.ACTIVE) {
                return loadFinalized(connection, session);
            }
            long now = clock.millis();
            SessionStatus status = now >= session.deadline()
                    ? SessionStatus.AUTO_SUBMITTED : SessionStatus.SUBMITTED;
            return finalizeSession(connection, session, status, now);
        });
    }

    @Override
    public Optional<FinalizedSession> autoSubmitIfExpired(String sessionId) {
        return transact(connection -> {
            ExamSession session = loadSession(connection, sessionId, true);
            if (session == null || session.status() != SessionStatus.ACTIVE) {
                return Optional.empty();
            }
            long now = clock.millis();
            if (now < session.deadline()) {
                return Optional.empty();
            }
            return Optional.of(finalizeSession(connection, session, SessionStatus.AUTO_SUBMITTED, now));
        });
    }

    @Override
    public List<String> findExpiredSessionIds() {
        List<String> ids = new ArrayList<>();
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT session_id FROM exam_session WHERE status = 'ACTIVE' AND deadline <= ?"
             )) {
            statement.setLong(1, clock.millis());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    ids.add(result.getString("session_id"));
                }
            }
            return List.copyOf(ids);
        } catch (SQLException error) {
            throw databaseFailure(error);
        }
    }

    @Override
    public Optional<FinalizedSession> findFinalized(String sessionId) {
        try (Connection connection = database.open()) {
            ExamSession session = loadSession(connection, sessionId, false);
            return session == null || session.status() == SessionStatus.ACTIVE
                    || session.status() == SessionStatus.NEW
                    ? Optional.empty() : Optional.of(loadFinalized(connection, session));
        } catch (SQLException error) {
            throw databaseFailure(error);
        }
    }

    private FinalizedSession finalizeSession(
            Connection connection, ExamSession session, SessionStatus status, long submittedAt
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE exam_session SET status = ?, submitted_at = ? WHERE session_id = ?"
        )) {
            statement.setString(1, status.name());
            statement.setLong(2, submittedAt);
            statement.setString(3, session.sessionId());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO submission (session_id, submitted_at, submit_type) VALUES (?, ?, ?)"
        )) {
            statement.setString(1, session.sessionId());
            statement.setLong(2, submittedAt);
            statement.setString(3, status == SessionStatus.SUBMITTED ? "MANUAL" : "AUTO");
            statement.executeUpdate();
        }
        return new FinalizedSession(session.sessionId(), status, submittedAt, true);
    }

    private FinalizedSession loadFinalized(Connection connection, ExamSession session) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT submitted_at FROM exam_session WHERE session_id = ?"
        )) {
            statement.setString(1, session.sessionId());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getObject("submitted_at") == null) {
                    throw new ServerFailure(ErrorCode.INTERNAL_ERROR, "Finalized session has no submit timestamp");
                }
                return new FinalizedSession(session.sessionId(), session.status(), result.getLong("submitted_at"), false);
            }
        }
    }

    private void requireCandidate(Connection connection, String candidateId) throws SQLException {
        // Lock this candidate so two simultaneous JOIN_EXAM requests cannot create two sessions.
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT candidate_id FROM candidate WHERE candidate_id = ? FOR UPDATE"
        )) {
            statement.setString(1, candidateId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new ServerFailure(ErrorCode.CANDIDATE_NOT_ALLOWED, "Candidate is not registered");
                }
            }
        }
    }

    private ExamInfo loadActiveExam(Connection connection, String examId, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT title, start_time, end_time, status FROM exam WHERE exam_id = ?"
        )) {
            statement.setString(1, examId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new ServerFailure(ErrorCode.INVALID_REQUEST, "Exam was not found");
                }
                long startTime = result.getLong("start_time");
                long endTime = result.getLong("end_time");
                String status = result.getString("status");
                if (now < startTime || "DRAFT".equals(status) || "SCHEDULED".equals(status)) {
                    throw new ServerFailure(ErrorCode.EXAM_NOT_STARTED, "The exam has not started");
                }
                if (now >= endTime || !"ACTIVE".equals(status)) {
                    throw new ServerFailure(ErrorCode.EXAM_ENDED, "The exam has ended");
                }
                return new ExamInfo(result.getString("title"), endTime);
            }
        }
    }

    private ExamSession findByCandidateAndExam(Connection connection, String candidateId, String examId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT session_id, exam_id, candidate_id, status, started_at, deadline " +
                        "FROM exam_session WHERE candidate_id = ? AND exam_id = ?"
        )) {
            statement.setString(1, candidateId);
            statement.setString(2, examId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? mapSession(result) : null;
            }
        }
    }

    private ExamSession loadSession(Connection connection, String sessionId, boolean forUpdate) throws SQLException {
        String sql = "SELECT session_id, exam_id, candidate_id, status, started_at, deadline " +
                "FROM exam_session WHERE session_id = ?" + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? mapSession(result) : null;
            }
        }
    }

    private static ExamSession mapSession(ResultSet result) throws SQLException {
        return new ExamSession(
                result.getString("session_id"),
                result.getString("exam_id"),
                result.getString("candidate_id"),
                SessionStatus.valueOf(result.getString("status")),
                result.getLong("started_at"),
                result.getLong("deadline")
        );
    }

    private void insertSession(Connection connection, ExamSession session) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO exam_session " +
                        "(session_id, exam_id, candidate_id, status, started_at, deadline) " +
                        "VALUES (?, ?, ?, ?, ?, ?)"
        )) {
            statement.setString(1, session.sessionId());
            statement.setString(2, session.examId());
            statement.setString(3, session.candidateId());
            statement.setString(4, session.status().name());
            statement.setLong(5, session.startedAt());
            statement.setLong(6, session.deadline());
            statement.executeUpdate();
        }
    }

    private List<Payloads.QuestionView> loadQuestions(Connection connection, String examId) throws SQLException {
        Map<String, QuestionRow> rows = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT q.question_id, q.position_no, q.content AS question_content, " +
                        "o.option_id, o.option_label, o.content AS option_content " +
                        "FROM question q LEFT JOIN question_option o ON o.question_id = q.question_id " +
                        "WHERE q.exam_id = ? ORDER BY q.position_no, o.option_label"
        )) {
            statement.setString(1, examId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String questionId = result.getString("question_id");
                    QuestionRow row = rows.get(questionId);
                    if (row == null) {
                        row = new QuestionRow(
                                questionId,
                                result.getInt("position_no"),
                                result.getString("question_content")
                        );
                        rows.put(questionId, row);
                    }
                    String optionId = result.getString("option_id");
                    if (optionId == null) {
                        throw new ServerFailure(ErrorCode.INTERNAL_ERROR, "A question has no options");
                    }
                    row.options.add(new Payloads.OptionView(
                            optionId,
                            result.getString("option_label"),
                            result.getString("option_content")
                    ));
                }
            }
        }
        List<Payloads.QuestionView> questions = new ArrayList<>();
        for (QuestionRow row : rows.values()) {
            questions.add(new Payloads.QuestionView(
                    row.questionId, row.position, row.content, List.copyOf(row.options)
            ));
        }
        return List.copyOf(questions);
    }

    private Map<String, AnswerState> loadAnswers(Connection connection, String sessionId) throws SQLException {
        Map<String, AnswerState> answers = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT question_id, answer_version, selected_option_id, updated_at " +
                        "FROM answer WHERE session_id = ? ORDER BY question_id"
        )) {
            statement.setString(1, sessionId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    AnswerState answer = new AnswerState(
                            sessionId,
                            result.getString("question_id"),
                            result.getInt("answer_version"),
                            result.getString("selected_option_id"),
                            result.getLong("updated_at")
                    );
                    answers.put(answer.questionId(), answer);
                }
            }
        }
        return Map.copyOf(answers);
    }

    private AnswerState loadAnswer(Connection connection, String sessionId, String questionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT answer_version, selected_option_id, updated_at FROM answer " +
                        "WHERE session_id = ? AND question_id = ?"
        )) {
            statement.setString(1, sessionId);
            statement.setString(2, questionId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new AnswerState(
                        sessionId,
                        questionId,
                        result.getInt("answer_version"),
                        result.getString("selected_option_id"),
                        result.getLong("updated_at")
                ) : null;
            }
        }
    }

    private void requireQuestionAndOption(Connection connection, String examId, String questionId, String optionId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT question_id FROM question WHERE exam_id = ? AND question_id = ?"
        )) {
            statement.setString(1, examId);
            statement.setString(2, questionId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new ServerFailure(ErrorCode.QUESTION_NOT_FOUND, "Question does not belong to this exam");
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT option_id FROM question_option WHERE question_id = ? AND option_id = ?"
        )) {
            statement.setString(1, questionId);
            statement.setString(2, optionId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new ServerFailure(ErrorCode.INVALID_OPTION, "Option does not belong to this question");
                }
            }
        }
    }

    private void insertAnswer(Connection connection, AnswerState answer) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO answer " +
                        "(session_id, question_id, answer_version, selected_option_id, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?)"
        )) {
            bindAnswer(statement, answer);
            statement.executeUpdate();
        }
    }

    private void updateAnswer(Connection connection, AnswerState answer) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE answer SET answer_version = ?, selected_option_id = ?, updated_at = ? " +
                        "WHERE session_id = ? AND question_id = ?"
        )) {
            statement.setInt(1, answer.version());
            statement.setString(2, answer.selectedOptionId());
            statement.setLong(3, answer.updatedAt());
            statement.setString(4, answer.sessionId());
            statement.setString(5, answer.questionId());
            statement.executeUpdate();
        }
    }

    private static void bindAnswer(PreparedStatement statement, AnswerState answer) throws SQLException {
        statement.setString(1, answer.sessionId());
        statement.setString(2, answer.questionId());
        statement.setInt(3, answer.version());
        statement.setString(4, answer.selectedOptionId());
        statement.setLong(5, answer.updatedAt());
    }

    private <T> T transact(SqlWork<T> work) {
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException error) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    error.addSuppressed(rollbackError);
                }
                if (error instanceof SQLException sqlError) {
                    throw databaseFailure(sqlError);
                }
                throw (RuntimeException) error;
            }
        } catch (SQLException error) {
            throw databaseFailure(error);
        }
    }

    private static ServerFailure databaseFailure(SQLException error) {
        return new ServerFailure(ErrorCode.INTERNAL_ERROR, "Database operation failed", error);
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    private record ExamInfo(String title, long endTime) {
    }

    private static final class QuestionRow {
        private final String questionId;
        private final int position;
        private final String content;
        private final List<Payloads.OptionView> options = new ArrayList<>();

        private QuestionRow(String questionId, int position, String content) {
            this.questionId = questionId;
            this.position = position;
            this.content = content;
        }
    }
}
