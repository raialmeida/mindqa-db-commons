package br.com.mindqa.database.integration;

import java.sql.SQLException;
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
    void isolatesDifferentConnectionsAndDatabasesInTheSameTest() throws SQLException {
        String[] names = System.getProperty("db.integration.connections").split(",");
        assertTrue(names.length >= 2, "Informe pelo menos duas conexões nomeadas");
        String alternate = System.getProperty("db.integration.alternate", "qa_other");
        String table = "mindqa_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        List<String[]> created = new ArrayList<>();
        try {
            for (String name : names) {
                DatabaseClient client = DatabaseService.connection(name);
                for (String database : new String[]{null, alternate}) {
                    DatabaseClient databaseClient = client.database(database);
                    databaseClient.execute("CREATE TABLE " + table + " (id INTEGER PRIMARY KEY, nome VARCHAR(100))");
                    created.add(new String[]{name, database});
                    assertEquals(1, databaseClient.execute(
                            "INSERT INTO " + table + " VALUES (?, ?)", 1, name + ":" + database));
                }
            }
            for (String[] target : created) {
                List<Map<String, Object>> rows = DatabaseService.connection(target[0]).database(target[1]).select(
                        "SELECT nome FROM " + table + " WHERE id = ?", 1);
                assertEquals(1, rows.size());
                assertEquals(target[0] + ":" + target[1], rows.get(0).get("nome"));
            }
        } finally {
            SQLException cleanupFailure = null;
            for (String[] target : created) {
                try {
                    DatabaseService.connection(target[0]).database(target[1]).execute("DROP TABLE " + table);
                } catch (SQLException exception) {
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
    void executesCrudUsingActualConfiguredDriver() throws SQLException {
        String table = "mindqa_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        DatabaseService.execute("CREATE TABLE " + table + " (id INTEGER PRIMARY KEY, nome VARCHAR(100))");
        try {
            assertEquals(1, DatabaseService.execute(
                    "INSERT INTO " + table + " (id, nome) VALUES (?, ?)", 1, "D'Avila"));
            List<Map<String, Object>> rows = DatabaseService.select(
                    "SELECT id, nome FROM " + table + " WHERE id = ?", 1);
            assertEquals(1, rows.size());
            assertEquals("D'Avila", rows.get(0).get("nome"));
            assertEquals(1, DatabaseService.execute(
                    "UPDATE " + table + " SET nome = ? WHERE id = ?", null, 1));
            assertNull(DatabaseService.select("SELECT nome FROM " + table + " WHERE id = ?", 1).get(0).get("nome"));

            assertEquals(1, DatabaseService.select("SELECT id FROM " + table + " WHERE id = ?", 1).size());
            assertEquals(1, DatabaseService.execute("DELETE FROM " + table + " WHERE id = ?", 1));
            assertTrue(DatabaseService.select("SELECT id FROM " + table).isEmpty());
        } finally {
            DatabaseService.execute("DROP TABLE " + table);
        }
    }
}
