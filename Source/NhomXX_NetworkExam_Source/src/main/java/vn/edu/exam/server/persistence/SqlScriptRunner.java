package vn.edu.exam.server.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public final class SqlScriptRunner {
    private SqlScriptRunner() {
    }

    public static void run(Connection connection, String script) throws SQLException {
        StringBuilder cleaned = new StringBuilder();
        for (String line : script.lines().toList()) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("--") && !trimmed.isEmpty()) {
                cleaned.append(line).append('\n');
            }
        }
        for (String statementText : cleaned.toString().split(";")) {
            String sql = statementText.trim();
            if (!sql.isEmpty()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                }
            }
        }
    }
}

