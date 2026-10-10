package vn.edu.exam.common.protocol;

public enum MessageType {
    JOIN_EXAM,
    EXAM_STATE,
    ANSWER_UPDATE,
    ANSWER_ACK,
    RECONNECT,
    RESYNC_STATE,
    SUBMIT,
    SUBMIT_ACK,
    AUTO_SUBMIT,
    HEARTBEAT,
    HEARTBEAT_ACK,
    ERROR
}

