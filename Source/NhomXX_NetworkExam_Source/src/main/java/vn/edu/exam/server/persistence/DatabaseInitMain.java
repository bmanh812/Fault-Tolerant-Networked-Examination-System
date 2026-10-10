package vn.edu.exam.server.persistence;

import vn.edu.exam.common.config.AppConfig;

import java.nio.file.Path;

public final class DatabaseInitMain {
    private DatabaseInitMain() {
    }

    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.load(Path.of("config", "application.properties"));
        DatabaseManager database = new DatabaseManager(
                config.get("database.url"),
                config.get("database.user"),
                config.get("database.password")
        );
        database.initialize(Path.of(config.get("database.schema")));
        database.seed(Path.of(config.get("database.seed")));
        System.out.println("H2 database initialized and seeded.");
    }
}

