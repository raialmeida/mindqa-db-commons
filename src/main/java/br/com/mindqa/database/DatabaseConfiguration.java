package br.com.mindqa.database;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * Valores imutáveis das fontes de configuração, com precedência do ambiente
 * sobre o arquivo.
 */
final class DatabaseConfiguration {
    private static final Set<String> CONNECTION_KEYS = Set.of("DB_TYPE", "DB_HOST", "DB_PORT",
            "DB_USER", "DB_PASS", "DB_NAME", "DB_QUERY_TIMEOUT_SECONDS", "DB_LOGIN_TIMEOUT_SECONDS",
            "DB_POOL_ENABLED", "DB_POOL_MAX_SIZE", "DB_POOL_CONNECTION_TIMEOUT_MS");
    private static final Set<String> DATABASE_TYPE_PREFIXES = Set.of("SQLSERVER", "MYSQL", "POSTGRESQL", "ORACLE");
    private final Map<String, String> fileProperties;
    private final Map<String, String> environment;
    private final String connectionName;
    private final String aliasPrefix;
    private final boolean noFileSelected;

    DatabaseConfiguration(Properties properties, Map<String, String> environment) {
        this(properties, environment, false);
    }

    DatabaseConfiguration(Properties properties, Map<String, String> environment, boolean noFileSelected) {
        Map<String, String> snapshot = new HashMap<>();
        for (String key : properties.stringPropertyNames()) {
            snapshot.put(key, properties.getProperty(key));
        }
        this.fileProperties = Map.copyOf(snapshot);
        this.environment = Map.copyOf(environment);
        this.connectionName = null;
        this.aliasPrefix = null;
        this.noFileSelected = noFileSelected;
    }

    private DatabaseConfiguration(DatabaseConfiguration source, String connectionName, String aliasPrefix) {
        this.fileProperties = source.fileProperties;
        this.environment = source.environment;
        this.connectionName = connectionName;
        this.aliasPrefix = aliasPrefix;
        this.noFileSelected = source.noFileSelected;
    }

    DatabaseConfiguration forConnection(String requestedName) {
        String selectedName = requestedName;
        if (selectedName == null) {
            String defaultName = get("DB_DEFAULT_CONNECTION");
            if (defaultName != null && !defaultName.trim().isEmpty()) {
                selectedName = defaultName;
            } else if (CONNECTION_KEYS.stream().anyMatch(key -> get(key) != null)) {
                return this;
            } else {
                Set<String> names = connectionNames();
                if (names.size() > 1) {
                    throw new IllegalStateException("Há várias conexões configuradas. Defina DB_DEFAULT_CONNECTION"
                            + " ou use DatabaseService.connection(nome).");
                }
                if (names.isEmpty()) {
                    return this;
                }
                selectedName = names.iterator().next();
            }
        }
        String normalizedName = normalizeConnectionName(selectedName);
        String detectedPrefix = findAliasPrefix(normalizedName);
        DatabaseConfiguration selected = new DatabaseConfiguration(this, normalizedName, detectedPrefix);
        if (CONNECTION_KEYS.stream().noneMatch(key -> selected.get(key) != null)) {
            throw new IllegalStateException("Conexão '" + normalizedName + "' não configurada. Defina "
                    + selected.key("DB_TYPE") + " e suas credenciais." + selected.missingFileHint());
        }
        return selected;
    }

    static String normalizeConnectionName(String name) {
        if (name == null || !name.trim().matches("[a-zA-Z][a-zA-Z0-9]*")) {
            throw new IllegalArgumentException("O nome da conexão deve começar com uma letra e conter"
                    + " somente letras ASCII e números, por exemplo: principal ou erp2.");
        }
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private Set<String> connectionNames() {
        Set<String> names = new TreeSet<>();
        Set<String> keys = new TreeSet<>(fileProperties.keySet());
        keys.addAll(environment.keySet());
        for (String candidate : keys) {
            for (String key : CONNECTION_KEYS) {
                String suffix = key.substring("DB_".length());
                if (candidate.startsWith("DB_CONNECTIONS_") && candidate.endsWith("_" + suffix)
                        && candidate.length() > "DB_CONNECTIONS_".length() + suffix.length() + 1) {
                    String name = candidate.substring("DB_CONNECTIONS_".length(),
                            candidate.length() - suffix.length() - 1);
                    if (name.matches("[A-Z][A-Z0-9]*")) {
                        names.add(name.toLowerCase(Locale.ROOT));
                    }
                }
                String propertySuffix = "." + suffix.toLowerCase(Locale.ROOT).replace('_', '.');
                if (fileProperties.containsKey(candidate) && candidate.startsWith("db.connections.")
                        && candidate.endsWith(propertySuffix)
                        && candidate.length() > "db.connections.".length() + propertySuffix.length()) {
                    String name = candidate.substring("db.connections.".length(),
                            candidate.length() - propertySuffix.length());
                    if (name.matches("[a-z][a-z0-9]*")) {
                        names.add(name);
                    }
                }
                for (String type : DATABASE_TYPE_PREFIXES) {
                    String normalized = candidate.replace('.', '_').toUpperCase(Locale.ROOT);
                    if (normalized.equals(type + "_" + suffix)) {
                        names.add(type.toLowerCase(Locale.ROOT));
                    } else if (normalized.startsWith(type + "_") && normalized.endsWith("_" + suffix)
                            && normalized.length() > type.length() + suffix.length() + 2) {
                        String name = normalized.substring(type.length() + 1,
                                normalized.length() - suffix.length() - 1);
                        if (name.matches("[A-Z][A-Z0-9]*")) {
                            names.add(name.toLowerCase(Locale.ROOT));
                        }
                    }
                }
            }
        }
        return names;
    }

    String key(String key) {
        return aliasPrefix == null ? standardKey(key) : aliasPrefix + "_" + key.substring(3);
    }

    String connectionName() {
        return connectionName;
    }

    boolean booleanValue(String key, boolean defaultValue) {
        String value = get(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(value.trim())) {
            return true;
        }
        if ("false".equalsIgnoreCase(value.trim())) {
            return false;
        }
        throw new IllegalArgumentException(key(key) + " deve ser true ou false.");
    }

    private String standardKey(String key) {
        return connectionName == null ? key
                : "DB_CONNECTIONS_" + connectionName.toUpperCase(Locale.ROOT) + "_" + key.substring(3);
    }

    String get(String key) {
        String standard = standardKey(key);
        String alias = aliasPrefix == null ? standard : aliasPrefix + "_" + key.substring(3);
        String value = environment.get(standard);
        if (value == null) {
            value = environment.get(alias);
        }
        if (value == null) {
            value = fileValue(standard);
        }
        if (value == null) {
            value = fileValue(alias);
        }
        if ("DB_TYPE".equals(key) && aliasPrefix != null) {
            String inferred = aliasPrefix.split("_", 2)[0].toLowerCase(Locale.ROOT);
            if (value == null || value.trim().isEmpty()) {
                return inferred;
            }
            String explicit = value.trim().toLowerCase(Locale.ROOT);
            if (!inferred.equals(explicit) && !("postgresql".equals(inferred) && "postgres".equals(explicit))) {
                throw new IllegalArgumentException("O tipo da conexão '" + connectionName
                        + "' contradiz o prefixo " + aliasPrefix + ".");
            }
        }
        return value;
    }

    private String fileValue(String key) {
        String value = fileProperties.get(key);
        return value != null ? value : fileProperties.get(key.toLowerCase(Locale.ROOT).replace('_', '.'));
    }

    String required(String key) {
        String value = get(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException(
                    "Defina " + key(key) + " nas variáveis de ambiente ou no arquivo .properties selecionado."
                            + missingFileHint());
        }
        return value.trim();
    }

    String missingFileHint() {
        return noFileSelected ? " Nenhum arquivo foi selecionado e database.properties não foi encontrado no classpath."
                : "";
    }

    private String findAliasPrefix(String name) {
        Set<String> matches = new TreeSet<>();
        String upperName = name.toUpperCase(Locale.ROOT);
        for (String type : DATABASE_TYPE_PREFIXES) {
            if (type.equals(upperName) && hasAliasFields(type)) {
                matches.add(type);
            }
            String namedPrefix = type + "_" + upperName;
            if (hasAliasFields(namedPrefix)) {
                matches.add(namedPrefix);
            }
        }
        if (matches.size() > 1) {
            throw new IllegalStateException("A conexão '" + name + "' possui mais de um prefixo de configuração: "
                    + matches + ". Use nomes distintos para cada destino.");
        }
        return matches.isEmpty() ? null : matches.iterator().next();
    }

    private boolean hasAliasFields(String prefix) {
        return CONNECTION_KEYS.stream().anyMatch(key -> {
            String alias = prefix + "_" + key.substring(3);
            return environment.containsKey(alias) || fileValue(alias) != null;
        });
    }
}
