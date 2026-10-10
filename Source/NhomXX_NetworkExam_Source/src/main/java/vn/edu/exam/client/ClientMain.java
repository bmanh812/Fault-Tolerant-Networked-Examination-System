package vn.edu.exam.client;

import vn.edu.exam.common.config.AppConfig;

import java.nio.file.Path;

public final class ClientMain {
    private ClientMain() {
    }

    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.load(Path.of("config", "application.properties"));
        System.out.printf(
                "MCQ Client skeleton ready for %s:%d%n",
                config.get("client.serverHost"),
                config.getInt("client.serverPort")
        );
        System.out.println("Next P0 step: implement JOIN_EXAM request/EXAM_STATE response flow.");
    }
}

