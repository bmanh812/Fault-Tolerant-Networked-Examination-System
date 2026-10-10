package vn.edu.exam.client;

import vn.edu.exam.common.protocol.Payloads;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ClientExamState {
    private Snapshot current = Snapshot.empty();

    public void applyExamState(Payloads.ExamState examState) {
        Snapshot prepared = prepare(Objects.requireNonNull(examState, "examState is required"));
        synchronized (this) {
            current = prepared;
        }
    }

    public synchronized Snapshot snapshot() {
        return current;
    }

    public synchronized Map<String, AnswerDraft> answerSnapshot() {
        return current.answers();
    }

    public synchronized String sessionId() {
        return current.sessionId();
    }

    public synchronized String examId() {
        return current.examId();
    }

    public synchronized long deadline() {
        return current.deadline();
    }

    public synchronized long serverTime() {
        return current.serverTime();
    }

    public synchronized String sessionStatus() {
        return current.sessionStatus();
    }

    public synchronized List<Question> questions() {
        return current.questions();
    }

    private static Snapshot prepare(Payloads.ExamState examState) {
        requireText(examState.sessionId(), "sessionId");
        requireText(examState.examId(), "examId");
        requireText(examState.title(), "title");
        if (!"ACTIVE".equals(examState.status())) {
            throw new IllegalArgumentException("EXAM_STATE status must be ACTIVE");
        }
        if (examState.serverTime() <= 0) {
            throw new IllegalArgumentException("serverTime must be positive");
        }
        if (examState.deadline() <= examState.serverTime()) {
            throw new IllegalArgumentException("deadline must be after serverTime for an active exam");
        }
        if (examState.questions() == null || examState.questions().isEmpty()) {
            throw new IllegalArgumentException("questions must not be empty");
        }
        if (examState.answers() == null) {
            throw new IllegalArgumentException("answers is required");
        }

        List<Question> questions = new ArrayList<>();
        Map<String, Set<String>> optionIdsByQuestion = new LinkedHashMap<>();
        Set<String> questionIds = new HashSet<>();
        Set<Integer> positions = new HashSet<>();
        Map<String, AnswerDraft> answers = new LinkedHashMap<>();

        for (Payloads.QuestionView sourceQuestion : examState.questions()) {
            if (sourceQuestion == null) {
                throw new IllegalArgumentException("question must not be null");
            }
            String questionId = requireText(sourceQuestion.questionId(), "questionId");
            if (!questionIds.add(questionId)) {
                throw new IllegalArgumentException("Duplicate questionId: " + questionId);
            }
            if (sourceQuestion.position() < 1 || !positions.add(sourceQuestion.position())) {
                throw new IllegalArgumentException("Question position must be positive and unique");
            }
            requireText(sourceQuestion.content(), "question content");
            if (sourceQuestion.options() == null || sourceQuestion.options().isEmpty()) {
                throw new IllegalArgumentException("Question options must not be empty");
            }

            List<Option> options = new ArrayList<>();
            Set<String> optionIds = new HashSet<>();
            for (Payloads.OptionView sourceOption : sourceQuestion.options()) {
                if (sourceOption == null) {
                    throw new IllegalArgumentException("option must not be null");
                }
                String optionId = requireText(sourceOption.optionId(), "optionId");
                if (!optionIds.add(optionId)) {
                    throw new IllegalArgumentException("Duplicate optionId in question " + questionId + ": " + optionId);
                }
                options.add(new Option(
                        optionId,
                        requireText(sourceOption.label(), "option label"),
                        requireText(sourceOption.content(), "option content")
                ));
            }

            questions.add(new Question(
                    questionId,
                    sourceQuestion.position(),
                    sourceQuestion.content(),
                    options
            ));
            optionIdsByQuestion.put(questionId, optionIds);
            answers.put(questionId, new AnswerDraft(questionId, 0, 0, null, null));
        }

        for (Map.Entry<String, Payloads.AnswerView> entry : examState.answers().entrySet()) {
            String questionId = requireText(entry.getKey(), "answers key");
            Payloads.AnswerView sourceAnswer = Objects.requireNonNull(
                    entry.getValue(),
                    "answer must not be null"
            );
            if (!questionId.equals(sourceAnswer.questionId())) {
                throw new IllegalArgumentException("answers key must match answer.questionId");
            }
            Set<String> validOptions = optionIdsByQuestion.get(questionId);
            if (validOptions == null) {
                throw new IllegalArgumentException("Answer refers to unknown question: " + questionId);
            }
            if (sourceAnswer.version() < 1) {
                throw new IllegalArgumentException("Confirmed answer version must be at least 1");
            }
            String selectedOptionId = requireText(sourceAnswer.selectedOptionId(), "selectedOptionId");
            if (!validOptions.contains(selectedOptionId)) {
                throw new IllegalArgumentException("Answer option does not belong to question " + questionId);
            }
            answers.put(questionId, new AnswerDraft(
                    questionId,
                    sourceAnswer.version(),
                    sourceAnswer.version(),
                    selectedOptionId,
                    null
            ));
        }

        questions.sort((left, right) -> Integer.compare(left.position(), right.position()));
        return new Snapshot(
                examState.sessionId(),
                examState.examId(),
                examState.title(),
                examState.status(),
                examState.deadline(),
                examState.serverTime(),
                questions,
                answers
        );
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-blank string");
        }
        return value;
    }

    public record Option(String optionId, String label, String text) {
    }

    public record Question(String questionId, int position, String text, List<Option> options) {
        public Question {
            options = List.copyOf(options);
        }
    }

    public record Snapshot(
            String sessionId,
            String examId,
            String title,
            String sessionStatus,
            long deadline,
            long serverTime,
            List<Question> questions,
            Map<String, AnswerDraft> answers
    ) {
        public Snapshot {
            questions = List.copyOf(questions);
            answers = Collections.unmodifiableMap(new LinkedHashMap<>(answers));
        }

        private static Snapshot empty() {
            return new Snapshot(null, null, null, null, 0, 0, List.of(), Map.of());
        }
    }
}
