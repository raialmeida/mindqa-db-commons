package br.com.mindqa.database.integration;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import br.com.mindqa.database.DatabaseService;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Integração opt-in com um servidor real configurado por DB_* ou db.config. */
class DatabaseServiceIT {
    @Test
    void executesCrudUsingActualConfiguredDriver() {
        String table = "mindqa_it_" + UUID.randomUUID().toString().replace("-", "");
        DatabaseService.executeUpdate("CREATE TABLE " + table + " (id INTEGER PRIMARY KEY, nome VARCHAR(100))");
        try {
            assertEquals(1, DatabaseService.executeUpdate(
                    "INSERT INTO " + table + " (id, nome) VALUES (?, ?)", 1, "D'Avila"));
            List<Map<String, Object>> rows = DatabaseService.select(
                    "SELECT id, nome FROM " + table + " WHERE id = ?", 1);
            assertEquals(1, rows.size());
            assertEquals("D'Avila", rows.get(0).get("nome"));
            assertEquals(1, DatabaseService.executeUpdate(
                    "UPDATE " + table + " SET nome = ? WHERE id = ?", null, 1));
            assertNull(DatabaseService.select("SELECT nome FROM " + table + " WHERE id = ?", 1).get(0).get("nome"));

            String database = System.getenv("DB_NAME");
            assertEquals(1, DatabaseService.selectInDb(database, "SELECT id FROM " + table + " WHERE id = ?", 1).size());
            assertEquals(1, DatabaseService.executeUpdateInDb(database, "DELETE FROM " + table + " WHERE id = ?", 1));
            assertTrue(DatabaseService.select("SELECT id FROM " + table).isEmpty());
        } finally {
            DatabaseService.executeUpdate("DROP TABLE " + table);
        }
    }
}
