package vn.edu.exam.common.protocol;

public enum MessageType {
    PING,
    PONG,
    LOGIN_REQ,
    LOGIN_RESP,
    JOIN_REQ,
    JOIN_RESP,
    ANSWER_UPDATE,
    ANSWER_ACK,
    RESUME_REQ,
    RESUME_STATE,
    HEARTBEAT,
    HEARTBEAT_ACK,
    SUBMIT_REQ,
    SUBMIT_ACK,
    ERROR
}

