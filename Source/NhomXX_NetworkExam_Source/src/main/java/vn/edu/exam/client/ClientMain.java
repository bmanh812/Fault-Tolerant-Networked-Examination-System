package vn.edu.exam.client;

import vn.edu.exam.common.config.AppConfig;

import java.nio.file.Path;

public final class ClientMain {
    private ClientMain() {
    }

    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.load(Path.of("config", "application.properties"));
        ClientTransportConfig transportConfig = ClientTransportConfig.from(config);
        System.out.printf(
                "MCQ Client transport ready for %s:%d (connect=%dms, read=%dms, request=%dms)%n",
                config.get("client.serverHost"),
                config.getInt("client.serverPort"),
                transportConfig.connectTimeoutMs(),
                transportConfig.socketReadTimeoutMs(),
                transportConfig.requestTimeoutMs()
        );
        System.out.println("JOIN_EXAM business flow is intentionally not started in this phase.");
    }
}

