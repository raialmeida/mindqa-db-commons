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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseConfigurationLoaderTest {
    @TempDir
    Path directory;

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
