package br.com.mindqa.database;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/**
 * Lê o arquivo selecionado ou o recurso padrão e cria a configuração de uma
 * operação.
 */
final class DatabaseConfigurationLoader {
    private static final String DEFAULT_RESOURCE = "database.properties";
    private static final DatabaseConfigurationCache CACHE = new DatabaseConfigurationCache();

    private DatabaseConfigurationLoader() {
    }

    static DatabaseConfiguration load() {
        Properties selectors = new Properties();
        Properties system = System.getProperties();
        synchronized (system) {
            for (String key : new String[]{"db.config", "db.env"}) {
                String value = system.getProperty(key);
                if (value != null) {
                    selectors.setProperty(key, value);
                }
            }
        }
        return load(System.getenv(), selectors, Thread.currentThread().getContextClassLoader());
    }

    static void clearCache() {
        CACHE.clear();
    }

    static DatabaseConfiguration load(Map<String, String> environment, Properties systemProperties,
            ClassLoader classLoader) {
        Map<String, String> snapshot = Map.copyOf(environment);
        String location = selector("db.config", "DB_CONFIG", snapshot, systemProperties);
        if (location == null) {
            String profile = selector("db.env", "DB_ENV", snapshot, systemProperties);
            if (profile != null) {
                location = "classpath:database-" + profile + ".properties";
            }
        }

        boolean explicitSelection = location != null;
        if (!explicitSelection) {
            location = "classpath:" + DEFAULT_RESOURCE;
        }

        DatabaseConfigurationCache.Key cacheKey = new DatabaseConfigurationCache.Key(
                location, explicitSelection, classLoader, snapshot);
        long generation = CACHE.generation();
        DatabaseConfiguration cached = CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        DatabaseConfiguration loaded = read(location, explicitSelection, snapshot, classLoader);
        return loaded.booleanValue("DB_CONFIG_CACHE_ENABLED", false)
                ? CACHE.putIfAbsent(cacheKey, loaded, generation) : loaded;
    }

    private static DatabaseConfiguration read(String location, boolean explicitSelection,
            Map<String, String> snapshot, ClassLoader classLoader) {
        Properties properties = new Properties();
        try {
            InputStream input = explicitSelection ? open(location, classLoader)
                    : openDefaultResource(classLoader);
            if (input != null) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(input, StandardCharsets.UTF_8))) {
                    reader.mark(1);
                    if (reader.read() != '\uFEFF') {
                        reader.reset();
                    }
                    properties.load(reader);
                }
            }
            return new DatabaseConfiguration(properties, snapshot, input == null);
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Não foi possível carregar o arquivo de configuração '" + location
                            + "'. Verifique db.config/DB_CONFIG, db.env/DB_ENV ou database.properties.",
                    exception);
        }
    }

    private static String selector(String property, String variable, Map<String, String> environment,
            Properties systemProperties) {
        String value = systemProperties.getProperty(property);
        if (value == null || value.trim().isEmpty()) {
            value = environment.get(variable);
        }
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static InputStream open(String location, ClassLoader contextLoader) throws IOException {
        if (location.startsWith("file:")) {
            String path = location.substring("file:".length());
            if (path.startsWith("/")) {
                // file:///... pode ser o resultado de Path.toUri(), com espaços codificados.
                try {
                    return openFile(Path.of(URI.create(location)));
                } catch (IllegalArgumentException exception) {
                    return openFile(Path.of(path));
                }
            }
            return openFile(Path.of(path));
        }
        boolean classpathOnly = location.startsWith("classpath:");
        String resourceName = classpathOnly ? location.substring("classpath:".length()) : location;
        if (resourceName.startsWith("/")) {
            resourceName = resourceName.substring(1);
        }
        if (resourceName.isEmpty() || resourceName.endsWith("/")) {
            throw new IOException("Informe o caminho de um arquivo .properties.");
        }
        InputStream stream = openClasspathResource(resourceName, contextLoader);
        if (stream != null) {
            return stream;
        }
        if (classpathOnly) {
            throw new IOException("Recurso .properties não encontrado no classpath: " + resourceName);
        }
        return openFile(Path.of(location));
    }

    private static InputStream openClasspathResource(String resourceName, ClassLoader contextLoader) {
        InputStream stream = contextLoader == null ? null : contextLoader.getResourceAsStream(resourceName);
        return stream != null ? stream
                : DatabaseConfigurationLoader.class.getClassLoader().getResourceAsStream(resourceName);
    }

    private static InputStream openDefaultResource(ClassLoader contextLoader) {
        return contextLoader == null ? DatabaseConfigurationLoader.class.getClassLoader()
                .getResourceAsStream(DEFAULT_RESOURCE) : contextLoader.getResourceAsStream(DEFAULT_RESOURCE);
    }

    private static InputStream openFile(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("Arquivo .properties não encontrado ou caminho não é um arquivo: " + path);
        }
        return Files.newInputStream(path);
    }
}
