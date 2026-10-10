package vn.edu.exam.server.service;

import vn.edu.exam.server.model.AnswerState;

import java.util.Map;

public interface AnswerService {
    AnswerState saveIfNewer(
            String sessionId,
            String questionId,
            int version,
            String selectedOptionId,
            long receivedAt
    );

    Map<String, AnswerState> getAuthoritativeAnswers(String sessionId);
}
