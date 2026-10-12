package vn.edu.exam.client;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable local state for one MCQ answer.
 */
public record AnswerDraft(
        String questionId,
        int localVersion,
        int confirmedVersion,
        String selectedOptionId,
        UpdateSnapshot pendingSnapshot
) {
    public AnswerDraft {
        requireText(questionId, "questionId");
        if (localVersion < 0) {
            throw new IllegalArgumentException("localVersion must not be negative");
        }
        if (confirmedVersion < 0 || confirmedVersion > localVersion) {
            throw new IllegalArgumentException("confirmedVersion must be between 0 and localVersion");
        }
        if (localVersion == 0 && selectedOptionId != null) {
            throw new IllegalArgumentException("An unanswered question cannot have a selected option");
        }
        if (localVersion > 0) {
            requireText(selectedOptionId, "selectedOptionId");
        }
        if (pendingSnapshot != null) {
            if (!questionId.equals(pendingSnapshot.questionId())) {
                throw new IllegalArgumentException("Pending snapshot belongs to another question");
            }
            if (pendingSnapshot.version() > localVersion) {
                throw new IllegalArgumentException("Pending snapshot cannot be newer than local state");
            }
            if (pendingSnapshot.version() <= confirmedVersion) {
                throw new IllegalArgumentException("Pending snapshot must be newer than confirmed state");
            }
            if (pendingSnapshot.version() == localVersion
                    && !Objects.equals(selectedOptionId, pendingSnapshot.selectedOptionId())) {
                throw new IllegalArgumentException("Pending snapshot option does not match its local version");
            }
        }
    }

    public static AnswerDraft unanswered(String questionId) {
        return new AnswerDraft(questionId, 0, 0, null, null);
    }

    public static AnswerDraft confirmed(String questionId, int version, String selectedOptionId) {
        if (version < 1) {
            throw new IllegalArgumentException("Confirmed answer version must be at least 1");
        }
        return new AnswerDraft(questionId, version, version, selectedOptionId, null);
    }

    public AnswerDraft select(String optionId) {
        requireText(optionId, "optionId");
        if (Objects.equals(selectedOptionId, optionId)) {
            return this;
        }
        return new AnswerDraft(
                questionId,
                localVersion + 1,
                confirmedVersion,
                optionId,
                pendingSnapshot
        );
    }

    /**
     * Creates the immutable snapshot for the latest local change. If that version already has a
     * pending snapshot, the existing snapshot is retained so a retry cannot change its identity.
     */
    public AnswerDraft prepareUpdate(String sessionId, String requestId) {
        requireText(sessionId, "sessionId");
        requireUuid(requestId);
        if (localVersion == confirmedVersion) {
            throw new IllegalStateException("There is no unsaved answer change");
        }
        if (pendingSnapshot != null && pendingSnapshot.version() == localVersion) {
            return this;
        }
        return new AnswerDraft(
                questionId,
                localVersion,
                confirmedVersion,
                selectedOptionId,
                new UpdateSnapshot(requestId, sessionId, questionId, localVersion, selectedOptionId)
        );
    }

    public UpdateSnapshot retrySnapshot() {
        if (pendingSnapshot == null) {
            throw new IllegalStateException("There is no pending answer update to retry");
        }
        return pendingSnapshot;
    }

    /**
     * Applies confirmation for a snapshot that was actually sent. Stale and duplicate ACKs are
     * idempotent and never replace the latest local selection.
     */
    public AnswerDraft acknowledge(UpdateSnapshot acknowledgedSnapshot) {
        Objects.requireNonNull(acknowledgedSnapshot, "acknowledgedSnapshot is required");
        if (!questionId.equals(acknowledgedSnapshot.questionId())) {
            throw new IllegalArgumentException("ACK snapshot belongs to another question");
        }
        if (acknowledgedSnapshot.version() > localVersion) {
            throw new IllegalArgumentException("ACK version is newer than local state");
        }
        if (acknowledgedSnapshot.version() <= confirmedVersion) {
            return this;
        }
        if (acknowledgedSnapshot.version() == localVersion
                && !Objects.equals(selectedOptionId, acknowledgedSnapshot.selectedOptionId())) {
            throw new IllegalArgumentException("ACK option does not match the local version");
        }
        if (pendingSnapshot != null
                && pendingSnapshot.version() == acknowledgedSnapshot.version()
                && !pendingSnapshot.equals(acknowledgedSnapshot)) {
            throw new IllegalArgumentException("ACK does not match the pending snapshot");
        }

        UpdateSnapshot remainingPending = pendingSnapshot;
        if (acknowledgedSnapshot.equals(pendingSnapshot)) {
            remainingPending = null;
        }
        return new AnswerDraft(
                questionId,
                localVersion,
                acknowledgedSnapshot.version(),
                selectedOptionId,
                remainingPending
        );
    }

    public boolean isSaved() {
        return confirmedVersion == localVersion && pendingSnapshot == null;
    }

    public SaveStatus saveStatus() {
        if (isSaved()) {
            return SaveStatus.SAVED;
        }
        if (pendingSnapshot != null && pendingSnapshot.version() == localVersion) {
            return SaveStatus.SAVING;
        }
        return SaveStatus.PENDING;
    }

    /**
     * Compatibility alias for callers that display confirmed/saved state.
     */
    public boolean isConfirmed() {
        return isSaved();
    }

    public String pendingRequestId() {
        return pendingSnapshot == null ? null : pendingSnapshot.requestId();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-blank string");
        }
        return value;
    }

    private static void requireUuid(String requestId) {
        requireText(requestId, "requestId");
        try {
            if (!UUID.fromString(requestId).toString().equalsIgnoreCase(requestId)) {
                throw new IllegalArgumentException("requestId must be a canonical UUID");
            }
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("requestId must be a canonical UUID", error);
        }
    }

    public record UpdateSnapshot(
            String requestId,
            String sessionId,
            String questionId,
            int version,
            String selectedOptionId
    ) {
        public UpdateSnapshot {
            requireUuid(requestId);
            requireText(sessionId, "sessionId");
            requireText(questionId, "questionId");
            if (version < 1) {
                throw new IllegalArgumentException("version must be at least 1");
            }
            requireText(selectedOptionId, "selectedOptionId");
        }
    }

    public enum SaveStatus {
        SAVED,
        PENDING,
        SAVING
    }
}
