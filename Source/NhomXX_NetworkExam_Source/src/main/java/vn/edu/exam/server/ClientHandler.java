package vn.edu.exam.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.exam.common.protocol.ErrorCode;
import vn.edu.exam.common.protocol.PayloadCodec;
import vn.edu.exam.common.protocol.Payloads;
import vn.edu.exam.common.protocol.ProtocolIO;
import vn.edu.exam.common.protocol.ProtocolMessage;
import vn.edu.exam.common.protocol.ProtocolValidator;
import vn.edu.exam.server.service.AnswerService;
import vn.edu.exam.server.service.ServerFailure;
import vn.edu.exam.server.service.SessionService;
import vn.edu.exam.server.service.SubmissionService;

import java.io.IOException;
import java.net.Socket;
import java.util.UUID;

import static vn.edu.exam.common.protocol.MessageType.ANSWER_ACK;
import static vn.edu.exam.common.protocol.MessageType.ANSWER_UPDATE;
import static vn.edu.exam.common.protocol.MessageType.ERROR;
import static vn.edu.exam.common.protocol.MessageType.EXAM_STATE;
import static vn.edu.exam.common.protocol.MessageType.JOIN_EXAM;
import static vn.edu.exam.common.protocol.MessageType.SUBMIT;
import static vn.edu.exam.common.protocol.MessageType.SUBMIT_ACK;

final class ClientHandler implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientHandler.class);

    private final Socket socket;
    private final int maxMessageBytes;
    private final SessionService sessions;
    private final AnswerService answers;
    private final SubmissionService submissions;
    private final SessionConnectionRegistry connections;

    ClientHandler(
            Socket socket,
            int maxMessageBytes,
            SessionService sessions,
            AnswerService answers,
            SubmissionService submissions,
            SessionConnectionRegistry connections
    ) {
        this.socket = socket;
        this.maxMessageBytes = maxMessageBytes;
        this.sessions = sessions;
        this.answers = answers;
        this.submissions = submissions;
        this.connections = connections;
    }

    @Override
    public void run() {
        String remote = String.valueOf(socket.getRemoteSocketAddress());
        LOGGER.info("Client connected: {}", remote);
        ProtocolIO protocol = null;
        String boundSessionId = null;
        try (socket) {
            protocol = new ProtocolIO(
                    socket.getInputStream(),
                    socket.getOutputStream(),
                    maxMessageBytes
            );
            ProtocolMessage request;
            while ((request = protocol.read()) != null) {
                try {
                    ProtocolValidator.validateClientMessage(request);
                    ProtocolMessage response = dispatch(request, boundSessionId);
                    protocol.write(response);
                    if (request.type() == JOIN_EXAM) {
                        if (boundSessionId != null) {
                            connections.unbind(boundSessionId, protocol);
                        }
                        boundSessionId = (String) response.payload().get("sessionId");
                        connections.bind(boundSessionId, protocol);
                        long deadline = ((Number) response.payload().get("deadline")).longValue();
                        try {
                            submissions.schedule(boundSessionId, deadline);
                            ProtocolIO joinedProtocol = protocol;
                            submissions.notifyIfAlreadyAutoSubmitted(
                                    boundSessionId,
                                    event -> connections.push(joinedProtocol, event)
                            );
                        } catch (RuntimeException error) {
                            LOGGER.error("Could not check auto-submit state for {}", boundSessionId, error);
                        }
                    }
                } catch (ServerFailure failure) {
                    if (failure.code() == ErrorCode.INTERNAL_ERROR) {
                        LOGGER.error("Request {} failed", request.requestId(), failure);
                    } else {
                        LOGGER.info("Request {} rejected: {}", request.requestId(), failure.getMessage());
                    }
                    protocol.write(errorResponse(request, failure.code(), failure.getMessage(), failure.currentAnswer()));
                } catch (IllegalArgumentException invalid) {
                    if (!hasReliableRequestId(request)) {
                        throw invalid;
                    }
                    ErrorCode code = request.protocolVersion() == ProtocolMessage.VERSION_1
                            ? ErrorCode.INVALID_REQUEST : ErrorCode.UNSUPPORTED_VERSION;
                    protocol.write(errorResponse(request, code, invalid.getMessage(), null));
                }
            }
        } catch (IllegalArgumentException error) {
            LOGGER.warn("Invalid message from {}: {}", remote, error.getMessage());
        } catch (IOException error) {
            LOGGER.info("Client {} disconnected: {}", remote, error.getMessage());
        } catch (RuntimeException error) {
            LOGGER.error("Client handler failed for {}", remote, error);
        } finally {
            if (boundSessionId != null && protocol != null) {
                connections.unbind(boundSessionId, protocol);
            }
            LOGGER.info("Client closed: {}", remote);
        }
    }

    private ProtocolMessage dispatch(ProtocolMessage request, String boundSessionId) {
        if (request.type() == JOIN_EXAM) {
            Payloads.JoinExamRequest join = PayloadCodec.fromMap(
                    request.payload(), Payloads.JoinExamRequest.class
            );
            return ProtocolMessage.response(EXAM_STATE, request, PayloadCodec.toMap(
                    sessions.joinExam(join.candidateId(), join.examId())
            ));
        }
        if (request.type() == ANSWER_UPDATE) {
            Payloads.AnswerUpdate update = PayloadCodec.fromMap(
                    request.payload(), Payloads.AnswerUpdate.class
            );
            requireBoundSession(boundSessionId, update.sessionId());
            return ProtocolMessage.response(ANSWER_ACK, request, PayloadCodec.toMap(
                    answers.saveIfNewer(update)
            ));
        }
        if (request.type() == SUBMIT) {
            Payloads.SubmitRequest submit = PayloadCodec.fromMap(
                    request.payload(), Payloads.SubmitRequest.class
            );
            requireBoundSession(boundSessionId, submit.sessionId());
            return ProtocolMessage.response(SUBMIT_ACK, request, PayloadCodec.toMap(
                    submissions.submit(submit.sessionId())
            ));
        }
        throw new ServerFailure(
                ErrorCode.INVALID_REQUEST,
                "Business handler for " + request.type() + " is not implemented yet"
        );
    }

    private static void requireBoundSession(String boundSessionId, String requestedSessionId) {
        if (!requestedSessionId.equals(boundSessionId)) {
            throw new ServerFailure(ErrorCode.INVALID_SESSION, "Session is not bound to this connection");
        }
    }

    private static ProtocolMessage errorResponse(
            ProtocolMessage request,
            ErrorCode code,
            String message,
            Payloads.AnswerView currentAnswer
    ) {
        return new ProtocolMessage(
                ERROR,
                ProtocolMessage.VERSION_1,
                request.requestId(),
                PayloadCodec.toMap(new Payloads.ErrorResponse(code, message, currentAnswer))
        );
    }

    private static boolean hasReliableRequestId(ProtocolMessage request) {
        if (request.requestId() == null) {
            return false;
        }
        try {
            return UUID.fromString(request.requestId()).toString().equalsIgnoreCase(request.requestId());
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}

