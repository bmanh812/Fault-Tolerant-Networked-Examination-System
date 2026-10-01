package vn.edu.exam.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.exam.common.config.AppConfig;
import vn.edu.exam.server.persistence.DatabaseManager;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ServerMain {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerMain.class);

    private ServerMain() {
    }

    public static void main(String[] args) throws Exception {
        Path configPath = Path.of(args.length > 0 ? args[0] : "config/application.properties");
        AppConfig config = AppConfig.load(configPath);

        DatabaseManager database = new DatabaseManager(config);
        database.initialize(Path.of(config.get("database.schema")));

        int port = config.getInt("server.port");
        int workers = config.getInt("server.workerThreads");
        int readTimeout = config.getInt("server.socketReadTimeoutMs");
        int maxFrameBytes = config.getInt("protocol.maxFrameBytes");
        ExecutorService pool = Executors.newFixedThreadPool(workers);

        Runtime.getRuntime().addShutdownHook(new Thread(pool::shutdown));
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            LOGGER.info("Exam Server listening on port {} with {} worker threads", port, workers);
            while (!Thread.currentThread().isInterrupted()) {
                Socket socket = serverSocket.accept();
                socket.setSoTimeout(readTimeout);
                pool.submit(new ClientHandler(socket, maxFrameBytes));
            }
        } finally {
            pool.shutdown();
        }
    }
}

