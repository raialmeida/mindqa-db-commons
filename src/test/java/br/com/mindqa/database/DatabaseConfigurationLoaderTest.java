package br.com.mindqa.database;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseConfigurationLoaderTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource({"sqlserver,jdbc:sqlserver://,1433", "postgresql,jdbc:postgresql://,5432",
            "mysql,jdbc:mysql://,3306", "oracle,jdbc:oracle:thin:@//,1521"})
    void shippedPropertiesConfigureEveryDriverWithoutAnExplicitType(String name, String protocol, String port) throws Exception {
        DatabaseConfiguration selected = shippedConfiguration().forConnection(name);
        assertEquals(name, selected.get("DB_TYPE"));
        JdbcConnectionSettings settings = JdbcConnectionSettings.from(selected, null);
        assertTrue(settings.jdbcUrl().startsWith(protocol));
        assertTrue(settings.jdbcUrl().contains(":" + port));
        assertTrue(java.sql.DriverManager.getDriver(settings.jdbcUrl()).acceptsURL(settings.jdbcUrl()));
    }

    @Test
    void shippedPropertiesSelectPostgresAsDefaultAndAllowNamedPasswordOverrides() {
        assertEquals("postgresql", shippedConfiguration().forConnection(null).get("DB_TYPE"));
        Properties selectors = new Properties();
        selectors.setProperty("db.config", "classpath:database.properties");
        DatabaseConfiguration configuration = DatabaseConfigurationLoader.load(
                Map.of("ORACLE_PASS", "senha-exclusiva-do-pipeline"), selectors, getClass().getClassLoader());
        assertEquals("senha-exclusiva-do-pipeline", configuration.forConnection("oracle").get("DB_PASS"));
        assertEquals("postgresql", configuration.forConnection(null).get("DB_TYPE"));
    }

    private DatabaseConfiguration shippedConfiguration() {
        return load("classpath:database.properties");
    }

    @Test
    void loadsUtf8BomAndFileUriWithEncodedSpaces() throws Exception {
        Path file = directory.resolve("configuração qa.properties");
        Files.writeString(file, "\uFEFFDB_USER=usuário\nDB_PASS=\\ senha\\ \n", StandardCharsets.UTF_8);
        DatabaseConfiguration configuration = load(file.toUri().toString());
        assertEquals("usuário", configuration.get("DB_USER"));
        assertEquals(" senha ", configuration.get("DB_PASS"));
    }

    @Test
    void usesConsumerContextClassLoader() throws Exception {
        Files.writeString(directory.resolve("consumer.properties"), "db.name=consumer_database\n");
        try (URLClassLoader loader = new URLClassLoader(new URL[]{directory.toUri().toURL()}, null)) {
            Properties selectors = new Properties();
            selectors.setProperty("db.config", "classpath:consumer.properties");
            DatabaseConfiguration configuration = DatabaseConfigurationLoader.load(Map.of(), selectors, loader);
            assertEquals("consumer_database", configuration.get("DB_NAME"));
        }
    }

    @Test
    void settingsAreSnapshotOfEnvironmentAndFile() throws Exception {
        Path file = directory.resolve("snapshot.properties");
        Files.writeString(file, "DB_NAME=antes\n");
        Map<String, String> environment = new HashMap<>(Map.of("DB_USER", "original"));
        Properties selectors = new Properties();
        selectors.setProperty("db.config", file.toString());
        DatabaseConfiguration configuration = DatabaseConfigurationLoader.load(environment, selectors, null);
        environment.put("DB_USER", "alterado");
        Files.writeString(file, "DB_NAME=depois\n");
        assertEquals("original", configuration.get("DB_USER"));
        assertEquals("antes", configuration.get("DB_NAME"));
    }

    @Test
    void rejectsDirectoryInsteadOfPropertiesFile() {
        assertThrows(IllegalStateException.class, () -> load("file:" + directory));
    }

    @Test
    void rejectsEmptyClasspathResource() {
        assertThrows(IllegalStateException.class, () -> load("classpath:"));
    }

    private DatabaseConfiguration load(String path) {
        Properties selectors = new Properties();
        selectors.setProperty("db.config", path);
        return DatabaseConfigurationLoader.load(Map.of(), selectors, null);
    }
}
