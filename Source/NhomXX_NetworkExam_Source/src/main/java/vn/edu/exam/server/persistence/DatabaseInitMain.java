package vn.edu.exam.server.persistence;

import vn.edu.exam.common.config.AppConfig;

import java.nio.file.Files;
import java.nio.file.Path;

public final class DatabaseInitMain {
    private DatabaseInitMain() {
    }

    public static void main(String[] args) throws Exception {
        Path configPath = Path.of(args.length > 0 ? args[0] : "config/application.properties");
        AppConfig config = AppConfig.load(configPath);
        DatabaseManager database = new DatabaseManager(config);

        Path schemaPath = Path.of(config.get("database.schema"));
        Path seedPath = Path.of(config.get("database.seed"));
        database.initialize(schemaPath);
        try (var connection = database.open()) {
            SqlScriptRunner.run(connection, Files.readString(seedPath));
        }
        System.out.println("Database initialized: " + config.get("database.url"));
    }
}

