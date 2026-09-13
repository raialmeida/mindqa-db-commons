package br.com.mindqa.database.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import br.com.mindqa.database.DatabaseService;
import br.com.mindqa.database.DatabaseClient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Integração opt-in com um servidor real configurado por DB_* ou db.config. */
class DatabaseServiceIT {
    @Test
    @EnabledIfSystemProperty(named = "db.integration.connections", matches = ".+")
    void isolatesDifferentConnectionsAndDatabasesInTheSameTest() {
        String[] names = System.getProperty("db.integration.connections").split(",");
        assertTrue(names.length >= 2, "Informe pelo menos duas conexões nomeadas");
        String alternate = System.getProperty("db.integration.alternate", "qa_other");
        String table = "mindqa_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        List<String[]> created = new ArrayList<>();
        try {
            for (String name : names) {
                DatabaseClient client = DatabaseService.connection(name);
                for (String database : new String[]{null, alternate}) {
                    client.executeUpdateInDb(database, "CREATE TABLE " + table + " (id INTEGER PRIMARY KEY, nome VARCHAR(100))");
                    created.add(new String[]{name, database});
                    assertEquals(1, client.executeUpdateInDb(database,
                            "INSERT INTO " + table + " VALUES (?, ?)", 1, name + ":" + database));
                }
            }
            for (String[] target : created) {
                List<Map<String, Object>> rows = DatabaseService.connection(target[0]).selectInDb(target[1],
                        "SELECT nome FROM " + table + " WHERE id = ?", 1);
                assertEquals(1, rows.size());
                assertEquals(target[0] + ":" + target[1], rows.get(0).get("nome"));
            }
        } finally {
            RuntimeException cleanupFailure = null;
            for (String[] target : created) {
                try {
                    DatabaseService.connection(target[0]).executeUpdateInDb(target[1], "DROP TABLE " + table);
                } catch (RuntimeException exception) {
                    if (cleanupFailure == null) {
                        cleanupFailure = exception;
                    } else {
                        cleanupFailure.addSuppressed(exception);
                    }
                }
            }
            if (cleanupFailure != null) {
                throw cleanupFailure;
            }
        }
    }

    @Test
    void executesCrudUsingActualConfiguredDriver() {
        String table = "mindqa_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
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
