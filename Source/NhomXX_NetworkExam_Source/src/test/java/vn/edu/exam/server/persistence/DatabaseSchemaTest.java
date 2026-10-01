package vn.edu.exam.server.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DatabaseSchemaTest {
    @TempDir
    Path tempDirectory;

    @Test
    void createsSchemaAndLoadsSeedData() throws Exception {
        Path database = tempDirectory.resolve("test.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            SqlScriptRunner.run(connection, Files.readString(Path.of("data/schema.sql")));
            SqlScriptRunner.run(connection, Files.readString(Path.of("data/sample-data.sql")));

            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT COUNT(*) FROM candidate")) {
                result.next();
                assertEquals(3, result.getInt(1));
            }
            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT COUNT(*) FROM question")) {
                result.next();
                assertEquals(5, result.getInt(1));
            }
        }
    }
}


