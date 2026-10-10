package vn.edu.exam.server.persistence;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseSchemaTest {
    @Test
    void createsRequiredMcqTables() throws Exception {
        DatabaseManager database = new DatabaseManager(
                "jdbc:h2:mem:mcq_schema;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        database.initialize(Path.of("data", "schema.sql"));

        Set<String> tables = new HashSet<>();
        try (Connection connection = database.open();
             ResultSet result = connection.getMetaData().getTables(null, "PUBLIC", "%", new String[]{"TABLE"})) {
            while (result.next()) {
                tables.add(result.getString("TABLE_NAME"));
            }
        }

        assertTrue(tables.contains("CANDIDATE"));
        assertTrue(tables.contains("EXAM"));
        assertTrue(tables.contains("QUESTION"));
        assertTrue(tables.contains("QUESTION_OPTION"));
        assertTrue(tables.contains("EXAM_SESSION"));
        assertTrue(tables.contains("ANSWER"));
        assertTrue(tables.contains("SUBMISSION"));
    }
}

