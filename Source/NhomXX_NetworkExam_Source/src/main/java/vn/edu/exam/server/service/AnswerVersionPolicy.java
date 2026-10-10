package vn.edu.exam.server.service;

import java.util.Objects;

public final class AnswerVersionPolicy {
    public enum Decision {
        APPLY,
        DUPLICATE,
        STALE,
        CONFLICT
    }

    private AnswerVersionPolicy() {
    }

    public static Decision evaluate(
            int currentVersion,
            String currentOptionId,
            int incomingVersion,
            String incomingOptionId
    ) {
        if (incomingVersion > currentVersion) {
            return Decision.APPLY;
        }
        if (incomingVersion < currentVersion) {
            return Decision.STALE;
        }
        return Objects.equals(currentOptionId, incomingOptionId)
                ? Decision.DUPLICATE
                : Decision.CONFLICT;
    }
}
