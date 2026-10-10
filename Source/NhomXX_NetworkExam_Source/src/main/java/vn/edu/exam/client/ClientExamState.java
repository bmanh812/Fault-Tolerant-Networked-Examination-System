package vn.edu.exam.client;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ClientExamState {
    private String sessionId;
    private long deadline;
    private String sessionStatus;
    private final Map<String, AnswerDraft> answers = new LinkedHashMap<>();

    public synchronized void start(String sessionId, long deadline, String sessionStatus) {
        this.sessionId = sessionId;
        this.deadline = deadline;
        this.sessionStatus = sessionStatus;
    }

    public synchronized void putAnswer(AnswerDraft answer) {
        answers.put(answer.questionId(), answer);
    }

    public synchronized Map<String, AnswerDraft> answerSnapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(answers));
    }

    public synchronized String sessionId() {
        return sessionId;
    }

    public synchronized long deadline() {
        return deadline;
    }

    public synchronized String sessionStatus() {
        return sessionStatus;
    }
}
