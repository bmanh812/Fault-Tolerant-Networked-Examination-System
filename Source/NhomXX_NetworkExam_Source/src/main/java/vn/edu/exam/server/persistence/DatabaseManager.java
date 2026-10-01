package vn.edu.exam.server.persistence;

import vn.edu.exam.common.config.AppConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class DatabaseManager {
    private final String databaseUrl;
    private final int busyTimeoutMs;

    public DatabaseManager(AppConfig config) {
        this.databaseUrl = config.get("database.url");
        this.busyTimeoutMs = config.getInt("database.busyTimeoutMs");
    }

    public Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(databaseUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = " + busyTimeoutMs);
        }
        return connection;
    }

    public void initialize(Path schemaPath) throws SQLException, IOException {
        try (Connection connection = open()) {
            SqlScriptRunner.run(connection, Files.readString(schemaPath));
        }
    }
}

