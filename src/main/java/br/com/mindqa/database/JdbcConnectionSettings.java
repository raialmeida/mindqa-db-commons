package br.com.mindqa.database;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;

/** Valores validados e imutáveis usados durante uma única operação JDBC. */
final class JdbcConnectionSettings {
    private enum DatabaseType {
        SQLSERVER(1433), POSTGRESQL(5432);

        private final int defaultPort;

        DatabaseType(int defaultPort) {
            this.defaultPort = defaultPort;
        }
    }

    private final String databaseName;
    private final String jdbcUrl;
    private final String user;
    private final String password;
    private final int queryTimeoutSeconds;
    private final int loginTimeoutSeconds;

    private JdbcConnectionSettings(String databaseName, String jdbcUrl, String user, String password,
                                   int queryTimeoutSeconds, int loginTimeoutSeconds) {
        this.databaseName = databaseName;
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
        this.loginTimeoutSeconds = loginTimeoutSeconds;
    }

    static JdbcConnectionSettings from(DatabaseConfiguration configuration, String dbName) {
        DatabaseType type = parseDatabaseType(configuration.get("DB_TYPE"));
        String host = formatHost(configuration.required("DB_HOST"));
        int port = parseIntegerProperty(configuration, "DB_PORT", type.defaultPort, 1, 65535);
        String user = configuration.required("DB_USER");
        String password = configuration.get("DB_PASS");
        if (password == null) {
            throw new IllegalStateException(
                    "Defina DB_PASS nas variáveis de ambiente ou no arquivo .properties selecionado.");
        }
        String database = isBlank(dbName) ? configuration.required("DB_NAME") : dbName.trim();
        if (database.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("DB_NAME não pode conter caracteres de controle.");
        }

        String url = type == DatabaseType.SQLSERVER
                ? "jdbc:sqlserver://" + host + ":" + port + ";databaseName="
                        + escapeSqlServerDatabaseName(database) + ";encrypt=false;"
                : "jdbc:postgresql://" + host + ":" + port + "/"
                        + URLEncoder.encode(database, StandardCharsets.UTF_8).replace("+", "%20");

        return new JdbcConnectionSettings(database, url, user, password,
                parseIntegerProperty(configuration, "DB_QUERY_TIMEOUT_SECONDS", 0, 0, Integer.MAX_VALUE),
                parseIntegerProperty(configuration, "DB_LOGIN_TIMEOUT_SECONDS", 0, 0, 65535));
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

    Properties connectionProperties() {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        if (loginTimeoutSeconds > 0) {
            properties.setProperty("loginTimeout", Integer.toString(loginTimeoutSeconds));
        }
        return properties;
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
            default:
                throw new IllegalArgumentException("DB_TYPE inválido. Use sqlserver, postgres ou postgresql.");
        }
    }

    private static String formatHost(String value) {
        if (value.chars().anyMatch(character -> Character.isWhitespace(character)
                || Character.isISOControl(character) || ";/?#@=\\".indexOf(character) >= 0)) {
            throw new IllegalArgumentException("DB_HOST deve conter somente o host ou IP, sem parâmetros JDBC.");
        }
        if (value.contains(":")) {
            String address = value.startsWith("[") && value.endsWith("]")
                    ? value.substring(1, value.length() - 1) : value;
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
        throw new IllegalArgumentException(key + " deve ser um número inteiro entre " + minimum + " e " + maximum + ".");
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
