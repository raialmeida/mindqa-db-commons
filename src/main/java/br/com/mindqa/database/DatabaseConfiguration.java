package br.com.mindqa.database;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/** Valores imutáveis das fontes de configuração, com precedência do ambiente sobre o arquivo. */
final class DatabaseConfiguration {
    private final Map<String, String> fileProperties;
    private final Map<String, String> environment;

    DatabaseConfiguration(Properties properties, Map<String, String> environment) {
        Map<String, String> snapshot = new HashMap<>();
        for (String key : properties.stringPropertyNames()) {
            snapshot.put(key, properties.getProperty(key));
        }
        this.fileProperties = Map.copyOf(snapshot);
        this.environment = Map.copyOf(environment);
    }

    String get(String key) {
        String environmentValue = environment.get(key);
        if (environmentValue != null) {
            return environmentValue;
        }
        String value = fileProperties.get(key);
        return value != null ? value : fileProperties.get(key.toLowerCase(Locale.ROOT).replace('_', '.'));
    }

    String required(String key) {
        String value = get(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException(
                    "Defina " + key + " nas variáveis de ambiente ou no arquivo .properties selecionado.");
        }
        return value.trim();
    }
}
