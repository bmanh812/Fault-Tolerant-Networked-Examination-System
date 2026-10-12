package vn.edu.exam.client;

import vn.edu.exam.client.ui.CoreExamClientGateway;
import vn.edu.exam.client.ui.ExamUiController;
import vn.edu.exam.client.ui.SwingExamFrame;
import vn.edu.exam.common.config.AppConfig;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ClientMain {
    private ClientMain() {
    }

    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.load(Path.of("config", "application.properties"));
        ClientTransportConfig transportConfig = ClientTransportConfig.from(config);
        String defaultCandidateId = argument(args, 0);
        String defaultExamId = argument(args, 1);

        SwingUtilities.invokeLater(() -> {
            useSystemLookAndFeel();
            ExamClient client = new ExamClient(transportConfig);
            CoreExamClientGateway gateway = new CoreExamClientGateway(client);
            ExecutorService networkExecutor = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "exam-ui-network");
                thread.setDaemon(true);
                return thread;
            });
            SwingExamFrame frame = new SwingExamFrame(
                    config.get("client.serverHost"),
                    config.getInt("client.serverPort"),
                    defaultCandidateId,
                    defaultExamId
            );
            ExamUiController controller = new ExamUiController(
                    gateway,
                    frame,
                    networkExecutor,
                    SwingUtilities::invokeLater
            );
            frame.bind(controller);
            frame.setVisible(true);
        });
    }

    private static String argument(String[] args, int index) {
        return args.length > index ? args[index].trim() : "";
    }

    private static void useSystemLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // The cross-platform Swing look and feel is a safe fallback.
        }
    }
}
