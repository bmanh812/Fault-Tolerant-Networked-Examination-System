package vn.edu.exam.server.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class DatabaseManager {
    private final String url;
    private final String user;
    private final String password;

    public DatabaseManager(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    public Connection open() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    public void initialize(Path schemaPath) throws IOException, SQLException {
        executeScript(schemaPath);
    }

    public void seed(Path seedPath) throws IOException, SQLException {
        executeScript(seedPath);
    }

    private void executeScript(Path path) throws IOException, SQLException {
        String sql = Files.readString(path);
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            for (String command : sql.split(";")) {
                String normalized = command.lines()
                        .filter(line -> !line.stripLeading().startsWith("--"))
                        .reduce("", (left, right) -> left + System.lineSeparator() + right)
                        .trim();
                if (!normalized.isEmpty()) {
                    statement.execute(normalized);
                }
            }
        }
    }
}

