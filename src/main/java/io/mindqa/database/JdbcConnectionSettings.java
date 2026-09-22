package io.mindqa.database;

import java.net.URLEncoder;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Valores validados e imutáveis usados durante uma única operação JDBC. */
final class JdbcConnectionSettings {
    private static final Set<String> RESERVED_DRIVER_PROPERTIES = Set.of("user", "password", "servername",
            "portnumber", "databasename", "encrypt", "trustservercertificate", "logintimeout",
            "connecttimeout", "oracle.jdbc.logintimeout");
    private enum DatabaseType {
        SQLSERVER(1433), POSTGRESQL(5432), ORACLE(1521), MYSQL(3306);

        private final int defaultPort;

        DatabaseType(int defaultPort) {
            this.defaultPort = defaultPort;
        }
    }

    private final String databaseName;
    private final DatabaseType databaseType;
    private final String jdbcUrl;
    private final String user;
    private final String password;
    private final int queryTimeoutSeconds;
    private final int loginTimeoutSeconds;
    private final String connectionName;
    private final boolean poolEnabled;
    private final int poolMaxSize;
    private final int poolConnectionTimeoutMs;
    private final Map<String, String> driverProperties;

    private JdbcConnectionSettings(DatabaseType databaseType, String databaseName, String jdbcUrl, String user,
            String password,
            int queryTimeoutSeconds, int loginTimeoutSeconds, Map<String, String> driverProperties,
            DatabaseConfiguration configuration) {
        this.databaseName = databaseName;
        this.databaseType = databaseType;
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
        this.loginTimeoutSeconds = loginTimeoutSeconds;
        this.connectionName = configuration.connectionName();
        this.poolEnabled = configuration.booleanValue("DB_POOL_ENABLED", false);
        this.poolMaxSize = poolEnabled
                ? parseIntegerProperty(configuration, "DB_POOL_MAX_SIZE", 5, 1, Integer.MAX_VALUE) : 5;
        this.poolConnectionTimeoutMs = poolEnabled
                ? parseIntegerProperty(configuration, "DB_POOL_CONNECTION_TIMEOUT_MS", 30000, 1000, Integer.MAX_VALUE)
                : 30000;
        this.driverProperties = Map.copyOf(driverProperties);
    }

    static JdbcConnectionSettings from(DatabaseConfiguration configuration, String dbName) {
        DatabaseType type = parseDatabaseType(configuration.get("DB_TYPE"));
        String host = formatHost(configuration.required("DB_HOST"));
        int port = parseIntegerProperty(configuration, "DB_PORT", type.defaultPort, 1, 65535);
        String user = configuration.required("DB_USER");
        String password = configuration.get("DB_PASS");
        if (password == null) {
            throw new IllegalStateException(
                    "Defina " + configuration.key("DB_PASS")
                            + " nas variáveis de ambiente ou no arquivo .properties selecionado."
                            + configuration.missingFileHint());
        }
        String database = isBlank(dbName) ? configuration.required("DB_NAME") : dbName.trim();
        if (database.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("DB_NAME não pode conter caracteres de controle.");
        }

        String url;
        switch (type) {
            case SQLSERVER:
                String encrypt = parseSqlServerEncrypt(configuration);
                boolean trustServerCertificate = configuration.booleanValue("DB_TRUST_SERVER_CERTIFICATE", false);
                url = "jdbc:sqlserver://" + host + ":" + port + ";databaseName="
                        + escapeSqlServerDatabaseName(database) + ";encrypt=" + encrypt
                        + ";trustServerCertificate=" + trustServerCertificate + ";";
                break;
            case ORACLE:
                if (!database.matches("[a-zA-Z0-9_.$-]+")) {
                    throw new IllegalArgumentException(configuration.key("DB_NAME")
                            + " deve conter um service name Oracle com letras, números, _, ., $ ou -.");
                }
                url = "jdbc:oracle:thin:@//" + host + ":" + port + "/" + database;
                break;
            case MYSQL:
            case POSTGRESQL:
                String protocol = type == DatabaseType.MYSQL ? "mysql" : "postgresql";
                url = "jdbc:" + protocol + "://" + host + ":" + port + "/"
                        + URLEncoder.encode(database, StandardCharsets.UTF_8).replace("+", "%20");
                break;
            default:
                throw new IllegalStateException("Tipo JDBC não implementado.");
        }

        return new JdbcConnectionSettings(type, database, url, user, password,
                parseIntegerProperty(configuration, "DB_QUERY_TIMEOUT_SECONDS", 0, 0, Integer.MAX_VALUE),
                parseIntegerProperty(configuration, "DB_LOGIN_TIMEOUT_SECONDS", 0, 0, 65535),
                parseDriverProperties(configuration), configuration);
    }

    String databaseName() {
        return databaseName;
    }

    String jdbcUrl() {
        return jdbcUrl;
    }

    int queryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    String connectionName() {
        return connectionName;
    }

    boolean poolEnabled() {
        return poolEnabled;
    }

    int poolMaxSize() {
        return poolMaxSize;
    }

    int poolConnectionTimeoutMs() {
        return poolConnectionTimeoutMs;
    }

    Properties connectionProperties() {
        Properties properties = new Properties();
        driverProperties.forEach(properties::setProperty);
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        if (loginTimeoutSeconds > 0) {
            switch (databaseType) {
                case MYSQL:
                    properties.setProperty("connectTimeout", Integer.toString(loginTimeoutSeconds * 1000));
                    break;
                case ORACLE:
                    properties.setProperty("oracle.jdbc.loginTimeout", Integer.toString(loginTimeoutSeconds));
                    break;
                default:
                    properties.setProperty("loginTimeout", Integer.toString(loginTimeoutSeconds));
            }
        }
        return properties;
    }

    private static Map<String, String> parseDriverProperties(DatabaseConfiguration configuration) {
        String value = configuration.get("DB_DRIVER_PROPERTIES");
        if (isBlank(value)) {
            return Map.of();
        }
        Map<String, String> properties = new LinkedHashMap<>();
        Set<String> normalizedKeys = new HashSet<>();
        for (String entry : value.split("&", -1)) {
            int separator = entry.indexOf('=');
            if (separator <= 0) {
                throw invalidDriverProperties(configuration);
            }
            String key;
            String propertyValue;
            try {
                key = URLDecoder.decode(entry.substring(0, separator), StandardCharsets.UTF_8).trim();
                propertyValue = URLDecoder.decode(entry.substring(separator + 1), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException exception) {
                throw invalidDriverProperties(configuration);
            }
            String normalizedKey = key.toLowerCase(Locale.ROOT);
            if (!key.matches("[a-zA-Z][a-zA-Z0-9._-]*")
                    || RESERVED_DRIVER_PROPERTIES.contains(normalizedKey)
                    || !normalizedKeys.add(normalizedKey)) {
                throw invalidDriverProperties(configuration);
            }
            properties.put(key, propertyValue);
        }
        return properties;
    }

    private static IllegalArgumentException invalidDriverProperties(DatabaseConfiguration configuration) {
        return new IllegalArgumentException(configuration.key("DB_DRIVER_PROPERTIES")
                + " deve usar chave=valor&outra=valor, sem chaves duplicadas ou reservadas.");
    }

    private static DatabaseType parseDatabaseType(String value) {
        if (isBlank(value)) {
            return DatabaseType.SQLSERVER;
        }
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "sqlserver":
                return DatabaseType.SQLSERVER;
            case "postgres":
            case "postgresql":
                return DatabaseType.POSTGRESQL;
            case "oracle":
                return DatabaseType.ORACLE;
            case "mysql":
                return DatabaseType.MYSQL;
            default:
                throw new IllegalArgumentException(
                        "DB_TYPE inválido. Use sqlserver, postgres, postgresql, oracle ou mysql.");
        }
    }

    private static String parseSqlServerEncrypt(DatabaseConfiguration configuration) {
        String value = configuration.get("DB_ENCRYPT");
        if (isBlank(value)) {
            return "false";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized) || "false".equals(normalized) || "strict".equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException(configuration.key("DB_ENCRYPT")
                + " deve ser true, false ou strict.");
    }

    private static String formatHost(String value) {
        if (value.chars().anyMatch(character -> Character.isWhitespace(character)
                || Character.isISOControl(character) || ";/?#@=\\,()\"".indexOf(character) >= 0)) {
            throw new IllegalArgumentException("DB_HOST deve conter somente o host ou IP, sem parâmetros JDBC.");
        }
        if (value.contains(":")) {
            String address = value.startsWith("[") && value.endsWith("]")
                    ? value.substring(1, value.length() - 1)
                    : value;
            if (!address.matches("[0-9a-fA-F:.]+")) {
                throw new IllegalArgumentException("DB_HOST inválido. Configure a porta separadamente em DB_PORT.");
            }
            return "[" + address + "]";
        }
        if (value.contains("[") || value.contains("]")) {
            throw new IllegalArgumentException("DB_HOST contém colchetes inválidos.");
        }
        return value;
    }

    private static String escapeSqlServerDatabaseName(String value) {
        return value.matches("[a-zA-Z0-9_.-]+") ? value : "{" + value.replace("}", "}}") + "}";
    }

    private static int parseIntegerProperty(DatabaseConfiguration configuration, String key,
            int defaultValue, int minimum, int maximum) {
        String value = configuration.get(key);
        if (isBlank(value)) {
            return defaultValue;
        }
        try {
            int number = Integer.parseInt(value.trim());
            if (number >= minimum && number <= maximum) {
                return number;
            }
        } catch (NumberFormatException ignored) {
            // A mensagem abaixo descreve o campo sem expor seu valor.
        }
        throw new IllegalArgumentException(
                key + " deve ser um número inteiro entre " + minimum + " e " + maximum + ".");
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
