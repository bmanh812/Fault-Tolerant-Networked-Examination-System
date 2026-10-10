package vn.edu.exam.client;

import vn.edu.exam.common.config.AppConfig;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Scanner;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public final class ClientMain {
    private ClientMain() {
    }

    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.load(Path.of("config", "application.properties"));
        ClientTransportConfig transportConfig = ClientTransportConfig.from(config);
        Scanner input = new Scanner(System.in);
        String candidateId = readValue(args, 0, input, "Candidate ID: ");
        String examId = readValue(args, 1, input, "Exam ID: ");

        try (ExamClient client = new ExamClient(transportConfig)) {
            client.connect(
                    config.get("client.serverHost"),
                    config.getInt("client.serverPort")
            );
            ClientExamState.Snapshot exam = client.joinExam(candidateId, examId).get();
            printExam(exam);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof ExamJoinException joinError) {
                System.err.printf("JOIN_EXAM failed [%s]: %s%n", joinError.errorCode(), joinError.getMessage());
            } else if (cause instanceof TimeoutException) {
                System.err.println("JOIN_EXAM timed out: Server did not respond in time");
            } else {
                System.err.println("JOIN_EXAM failed: " + cause.getMessage());
            }
        }
    }

    private static String readValue(String[] args, int index, Scanner input, String prompt) {
        if (args.length > index && !args[index].isBlank()) {
            return args[index].trim();
        }
        System.out.print(prompt);
        return input.nextLine().trim();
    }

    private static void printExam(ClientExamState.Snapshot exam) {
        System.out.printf("%nJoined exam: %s (%s)%n", exam.title(), exam.examId());
        System.out.println("Session: " + exam.sessionId());
        System.out.println("Status: " + exam.sessionStatus());
        System.out.println("Deadline: " + Instant.ofEpochMilli(exam.deadline()));
        System.out.println("Server time: " + Instant.ofEpochMilli(exam.serverTime()));

        for (ClientExamState.Question question : exam.questions()) {
            System.out.printf("%n%d. %s%n", question.position(), question.text());
            for (ClientExamState.Option option : question.options()) {
                System.out.printf("   %s. %s%n", option.label(), option.text());
            }
            AnswerDraft answer = exam.answers().get(question.questionId());
            if (answer != null && answer.selectedOptionId() != null) {
                System.out.printf(
                        "   Current answer: %s (confirmed version %d)%n",
                        answer.selectedOptionId(),
                        answer.confirmedVersion()
                );
            }
        }
    }
}
