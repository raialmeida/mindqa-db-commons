package br.com.mindqa.database;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import br.com.mindqa.database.support.JdbcScenarioRunner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseServiceTest {
    private static final String SQLSERVER_URL =
            "jdbc:sqlserver://database.test:1433;databaseName=qa_default;encrypt=false;";
    private static final String POSTGRES_URL = "jdbc:postgresql://database.test:5432/qa_default";

    @TempDir
    Path temporaryDirectory;

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "\n\t"})
    void rejectsInvalidSqlBeforeLoadingConfiguration(String sql) {
        assertThrows(IllegalArgumentException.class, () -> DatabaseService.select(sql));
        assertThrows(IllegalArgumentException.class, () -> DatabaseService.selectInDb("qa", sql));
        assertThrows(IllegalArgumentException.class, () -> DatabaseService.executeUpdate(sql));
        assertThrows(IllegalArgumentException.class, () -> DatabaseService.executeUpdateInDb("qa", sql));
    }

    @Test
    void rejectsNullParameterArrayBeforeLoadingConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> DatabaseService.select("SELECT 1", (Object[]) null));
        assertThrows(IllegalArgumentException.class, () -> DatabaseService.executeUpdate("DELETE FROM clientes", (Object[]) null));
    }

    @Test
    void keepsOriginalDatabaseWhenFileChangesDuringConnectionFailure() throws Exception {
        Path file = writeProperties("changing.properties", properties());
        run(Map.of(), "changed-config-error", POSTGRES_URL, "-", "-Ddb.config=" + file,
                "-Dscenario.replace.config=" + file, "-Dscenario.password=sênha=ação");
    }

    @ParameterizedTest
    @ValueSource(strings = {"statement-error", "statement-and-close-error", "close-error"})
    void preservesDriverExceptionAndResourceCleanup(String action) throws Exception {
        run(environment("DB_TYPE", "postgres"), action, POSTGRES_URL, "-");
    }

    @Test
    void appliesTimeoutsToConnectionsAndStatements() throws Exception {
        Map<String, String> environment = environment("DB_TYPE", "postgres");
        environment.put("DB_QUERY_TIMEOUT_SECONDS", "12");
        environment.put("DB_LOGIN_TIMEOUT_SECONDS", "7");
        run(environment, "crud", POSTGRES_URL, "-", "-Dscenario.queryTimeout=12", "-Dscenario.loginTimeout=7");
    }

    @Test
    void supportsConcurrentCallsWithIndependentConnections() throws Exception {
        run(environment("DB_TYPE", "postgres"), "parallel", POSTGRES_URL, "-");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"sqlserver", " SQLSERVER ", "   "})
    void sqlServerUsesDefaultPortAndSupportsCrud(String type) throws Exception {
        run(environment("DB_TYPE", type), "crud", SQLSERVER_URL, "-");
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgres", "postgresql", " POSTGRES "})
    void postgresUsesDefaultPortAndSupportsCrud(String type) throws Exception {
        run(environment("DB_TYPE", type), "crud", POSTGRES_URL, "-");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sqlserver", "postgres"})
    void configuredPortIsUsedWithConfiguredType(String type) throws Exception {
        Map<String, String> environment = environment("DB_TYPE", type);
        environment.put("DB_PORT", "15432");
        String url = "sqlserver".equals(type)
                ? SQLSERVER_URL.replace(":1433", ":15432") : POSTGRES_URL.replace(":5432", ":15432");
        run(environment, "crud", url, "-");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sqlserver", "postgres"})
    void databaseOverrideWorksWithoutDbNameEnvironment(String type) throws Exception {
        Map<String, String> environment = environment("DB_TYPE", type);
        environment.remove("DB_NAME");
        String url = ("sqlserver".equals(type) ? SQLSERVER_URL : POSTGRES_URL)
                .replace("qa_default", "qa_other");
        run(environment, "crud", url, "qa_other");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void blankDatabaseOverrideFallsBackToEnvironment(String dbName) throws Exception {
        Map<String, String> environment = environment("DB_TYPE", "postgres");
        environment.put("DB_PORT", "  ");
        run(environment, "crud", POSTGRES_URL, dbName == null ? "<null>" : dbName);
    }

    @Test
    void passwordCanBeExplicitlyEmpty() throws Exception {
        run(environment("DB_PASS", ""), "crud", SQLSERVER_URL, "-");
    }

    @ParameterizedTest
    @CsvSource({"DB_TYPE,unsupported", "DB_PORT,abc", "DB_PORT,0", "DB_PORT,-1", "DB_PORT,65536"})
    void rejectsInvalidConfigurationBeforeOpeningConnection(String key, String value) throws Exception {
        run(environment(key, value), "invalid-config", key, "-");
    }

    @ParameterizedTest
    @CsvSource({"mysql,jdbc:mysql://database.test:3306/qa_default",
            "oracle,jdbc:oracle:thin:@//database.test:1521/qa_default"})
    void additionalDriversSupportDefaultConnectionCrud(String type, String url) throws Exception {
        run(environment("DB_TYPE", type), "crud", url, "-");
    }

    @ParameterizedTest
    @CsvSource({"mysql,jdbc:mysql://database.test:3306/qa_other",
            "oracle,jdbc:oracle:thin:@//database.test:1521/qa_other"})
    void namedClientsSupportCrudAndDatabaseOverrides(String type, String url) throws Exception {
        Map<String, String> named = new HashMap<>();
        environment("DB_TYPE", type).forEach((key, value) -> named.put("DB_CONNECTIONS_ERP_" + key.substring(3), value));
        named.remove("DB_CONNECTIONS_ERP_NAME");
        run(named, "crud", url, "qa_other", "-Dscenario.connection=erp",
                "-Dscenario.password=" + named.get("DB_CONNECTIONS_ERP_PASS"));
    }

    @Test
    void fourNamedConnectionsAreIsolatedDuringConcurrentCrud() throws Exception {
        StringBuilder properties = new StringBuilder("db.default.connection=principal\n");
        String[][] connections = {{"principal", "postgres", "pg.test"}, {"legado", "sqlserver", "sql.test"},
                {"erp", "oracle", "ora.test"}, {"loja", "mysql", "mysql.test"}};
        for (String[] connection : connections) {
            String prefix = "db.connections." + connection[0] + ".";
            properties.append(prefix).append("type=").append(connection[1]).append('\n')
                    .append(prefix).append("host=").append(connection[2]).append('\n')
                    .append(prefix).append("name=shared\n")
                    .append(prefix).append("user=user_").append(connection[0]).append('\n')
                    .append(prefix).append("pass=pass_").append(connection[0]).append('\n');
        }
        writeProperties("connections.properties", properties.toString());
        run(Map.of(), "multiple", "-", "-", "-Ddb.config=connections.properties");
    }

    @Test
    void fourEnginePrefixedConnectionsWorkTogetherWithoutTypeProperties() throws Exception {
        StringBuilder properties = new StringBuilder("DB_DEFAULT_CONNECTION=principal\n");
        String[][] connections = {{"principal", "POSTGRESQL", "pg.test"}, {"legado", "SQLSERVER", "sql.test"},
                {"erp", "ORACLE", "ora.test"}, {"loja", "MYSQL", "mysql.test"}};
        for (String[] connection : connections) {
            String prefix = connection[1] + "_" + connection[0].toUpperCase(java.util.Locale.ROOT) + "_";
            properties.append(prefix).append("HOST=").append(connection[2]).append('\n')
                    .append(prefix).append("NAME=shared\n")
                    .append(prefix).append("USER=user_").append(connection[0]).append('\n')
                    .append(prefix).append("PASS=pass_").append(connection[0]).append('\n');
        }
        writeProperties("typed.properties", properties.toString());
        run(Map.of(), "multiple", "-", "-", "-Ddb.config=typed.properties");
    }

    @Test
    void automaticallyUsesOnlyNamedConnection() throws Exception {
        writeProperties("connections.properties", properties().replace("DB_", "DB_CONNECTIONS_ONLY_"));
        run(Map.of(), "crud", POSTGRES_URL, "-", "-Ddb.config=connections.properties", "-Dscenario.password=sênha=ação");
    }

    @Test
    void rejectsAmbiguousDefaultBeforeOpeningConnections() throws Exception {
        run(Map.of("DB_CONNECTIONS_FIRST_TYPE", "oracle", "DB_CONNECTIONS_SECOND_TYPE", "mysql"),
                "missing-config", "DB_DEFAULT_CONNECTION", "-");
    }

    @Test
    void rejectsUnknownNamedConnectionBeforeOpeningConnections() throws Exception {
        run(environment("DB_TYPE", "postgres"), "missing-config", "não configurada", "-",
                "-Dscenario.connection=missing");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "erp-qa", "erp.qa", "erp_qa", "1erp", "ação"})
    void rejectsInvalidConnectionNames(String name) {
        assertThrows(IllegalArgumentException.class, () -> DatabaseService.connection(name));
    }

    @Test
    void namedClientValidatesArgumentsBeforeLoadingConfiguration() {
        DatabaseClient client = DatabaseService.connection("notConfigured");
        assertThrows(IllegalArgumentException.class, () -> client.select(" "));
        assertThrows(IllegalArgumentException.class, () -> client.selectInDb("qa", null));
        assertThrows(IllegalArgumentException.class, () -> client.executeUpdate(null));
        assertThrows(IllegalArgumentException.class, () -> client.executeUpdateInDb("qa", " "));
        assertThrows(IllegalArgumentException.class, () -> client.select("SELECT 1", (Object[]) null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DB_HOST", "DB_USER", "DB_PASS", "DB_NAME"})
    void rejectsMissingRequiredEnvironment(String key) throws Exception {
        run(environment(key, null), "missing-config", key, "-");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DB_HOST", "DB_USER", "DB_NAME"})
    void rejectsBlankRequiredEnvironment(String key) throws Exception {
        run(environment(key, "  "), "missing-config", key, "-");
    }

    @ParameterizedTest
    @CsvSource({"query-error,sqlserver", "query-error,postgres", "update-error,sqlserver",
            "update-error,postgres", "connection-error,sqlserver", "connection-error,postgres"})
    void preservesSqlExceptionAndClosesConnections(String action, String type) throws Exception {
        run(environment("DB_TYPE", type), action,
                "sqlserver".equals(type) ? SQLSERVER_URL : POSTGRES_URL, "-");
    }

    @ParameterizedTest
    @ValueSource(strings = {"application-qa.properties", "ambientes/hml.properties", "outro-nome.properties"})
    void loadsAnyNamedClasspathPropertiesWithUtf8Password(String resource) throws Exception {
        writeProperties(resource, properties());
        run(Map.of(), "crud", POSTGRES_URL, "-",
                "-Ddb.config=" + resource, "-Dscenario.password=sênha=ação");
    }

    @Test
    void acceptsLowercasePropertyKeys() throws Exception {
        String content = properties().replace("DB_TYPE", "db.type").replace("DB_HOST", "db.host")
                .replace("DB_PORT", "db.port").replace("DB_USER", "db.user")
                .replace("DB_PASS", "db.pass").replace("DB_NAME", "db.name");
        writeProperties("config.properties", content);
        run(Map.of(), "crud", POSTGRES_URL, "-",
                "-Ddb.config=config.properties", "-Dscenario.password=sênha=ação");
    }

    @Test
    void uppercaseKeysTakePrecedenceOverAliases() throws Exception {
        writeProperties("config.properties", properties()
                + "db.type=sqlserver\ndb.host=wrong-host\ndb.port=1111\n"
                + "db.user=wrong-user\ndb.pass=wrong-password\ndb.name=wrong-database\n");
        run(Map.of(), "crud", POSTGRES_URL, "-",
                "-Ddb.config=config.properties", "-Dscenario.password=sênha=ação");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "file:"})
    void loadsAnExternalFile(String prefix) throws Exception {
        Path file = writeProperties("external config.properties", properties());
        run(Map.of(), "crud", POSTGRES_URL, "-",
                "-Ddb.config=" + prefix + file, "-Dscenario.password=sênha=ação");
    }

    @Test
    void loadsResourcePackagedInJar() throws Exception {
        try (JarOutputStream jar = new JarOutputStream(
                Files.newOutputStream(temporaryDirectory.resolve("fixtures.jar")))) {
            jar.putNextEntry(new JarEntry("packaged/qa.properties"));
            jar.write(properties().getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        run(Map.of(), "crud", POSTGRES_URL, "-",
                "-Ddb.config=classpath:/packaged/qa.properties", "-Dscenario.password=sênha=ação");
    }

    @Test
    void allEnvironmentValuesOverrideFileIncludingEmptyPassword() throws Exception {
        writeProperties("config.properties", "DB_TYPE=sqlserver\nDB_HOST=wrong-host\nDB_PORT=1111\n"
                + "DB_USER=wrong-user\nDB_PASS=wrong-password\nDB_NAME=wrong-database\n");
        Map<String, String> environment = environment("DB_TYPE", "postgres");
        environment.put("DB_PORT", "6543");
        environment.put("DB_PASS", "");
        run(environment, "crud", POSTGRES_URL.replace(":5432", ":6543"), "-",
                "-Ddb.config=config.properties");
    }

    @Test
    void blankEnvironmentTypeAndPortUseDefaultsInsteadOfFile() throws Exception {
        writeProperties("config.properties", properties());
        run(Map.of("DB_TYPE", " ", "DB_PORT", " "), "crud", SQLSERVER_URL, "-",
                "-Ddb.config=config.properties", "-Dscenario.password=sênha=ação");
    }

    @Test
    void combinesFileSettingsWithEnvironmentPassword() throws Exception {
        writeProperties("config.properties", properties().replace("DB_PASS=sênha=ação\n", ""));
        run(Map.of("DB_PASS", "senha-do-pipeline"), "crud", POSTGRES_URL, "-",
                "-Ddb.config=config.properties");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sqlserver", "postgres"})
    void databaseOverrideUsesTypeFromFile(String type) throws Exception {
        writeProperties("config.properties", properties().replace("DB_TYPE=postgres", "DB_TYPE=" + type)
                .replace("DB_PORT=5432\n", ""));
        String url = ("sqlserver".equals(type) ? SQLSERVER_URL : POSTGRES_URL)
                .replace("qa_default", "qa_other");
        run(Map.of(), "crud", url, "qa_other",
                "-Ddb.config=config.properties", "-Dscenario.password=sênha=ação");
    }

    @Test
    void supportsConfigSelectionThroughEnvironment() throws Exception {
        writeProperties("arbitrary.properties", properties());
        run(Map.of("DB_CONFIG", "arbitrary.properties"), "crud", POSTGRES_URL, "-",
                "-Dscenario.password=sênha=ação");
    }

    @Test
    void explicitConfigPropertyOverridesConfigEnvironmentAndProfile() throws Exception {
        writeProperties("selected.properties", properties());
        run(Map.of("DB_CONFIG", "missing.properties", "DB_ENV", "missing"), "crud", POSTGRES_URL, "-",
                "-Ddb.config=selected.properties", "-Ddb.env=missing", "-Dscenario.password=sênha=ação");
    }

    @Test
    void loadsProfileSelectedBySystemProperty() throws Exception {
        writeProperties("database-qa.properties", properties());
        run(Map.of("DB_ENV", "missing"), "crud", POSTGRES_URL, "-",
                "-Ddb.env=qa", "-Dscenario.password=sênha=ação");
    }

    @Test
    void loadsProfileSelectedByEnvironment() throws Exception {
        writeProperties("database-hml.properties", properties());
        run(Map.of("DB_ENV", "hml"), "crud", POSTGRES_URL, "-",
                "-Dscenario.password=sênha=ação");
    }

    @Test
    void doesNotAutomaticallyLoadUnselectedProperties() throws Exception {
        writeProperties("database.properties", "DB_TYPE=invalid\n");
        run(environment("DB_TYPE", null), "crud", SQLSERVER_URL, "-");
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing.properties", "classpath:missing.properties", "file:missing.properties"})
    void rejectsMissingSelectedFileEvenWithCompleteEnvironment(String location) throws Exception {
        run(environment("DB_TYPE", null), "file-error", location, "-", "-Ddb.config=" + location);
    }

    @Test
    void rejectsMissingProfile() throws Exception {
        run(Map.of(), "file-error", "database-missing.properties", "-", "-Ddb.env=missing");
    }

    @Test
    void rejectsMalformedProperties() throws Exception {
        writeProperties("broken.properties", "DB_PASS=" + '\\' + "uZZZZ\n");
        run(Map.of(), "file-error", "broken.properties", "-", "-Ddb.config=broken.properties");
    }

    @Test
    void validatesRequiredValuesFromFile() throws Exception {
        writeProperties("incomplete.properties", properties().replace("DB_USER=qa_user\n", ""));
        run(Map.of(), "missing-config", "DB_USER", "-", "-Ddb.config=incomplete.properties");
    }

    @Test
    void sqlErrorReportsDatabaseSelectedFromFile() throws Exception {
        writeProperties("config.properties", properties());
        run(Map.of(), "query-error", POSTGRES_URL, "-",
                "-Ddb.config=config.properties", "-Dscenario.password=sênha=ação");
    }

    private Path writeProperties(String name, String content) throws Exception {
        Path file = temporaryDirectory.resolve(name);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private String properties() {
        return "DB_TYPE=postgres\nDB_HOST=database.test\nDB_PORT=5432\n"
                + "DB_USER=qa_user\nDB_PASS=sênha=ação\nDB_NAME=qa_default\n";
    }

    private Map<String, String> environment(String key, String value) {
        Map<String, String> environment = new HashMap<>();
        environment.put("DB_HOST", "database.test");
        environment.put("DB_USER", "qa_user");
        environment.put("DB_PASS", "  senha;com=caracteres  ");
        environment.put("DB_NAME", "qa_default");
        if (value == null) {
            environment.remove(key);
        } else {
            environment.put(key, value);
        }
        return environment;
    }

    private void run(Map<String, String> environment, String action, String expected,
                     String database, String... javaOptions) throws Exception {
        new JdbcScenarioRunner(temporaryDirectory).run(environment, action, expected, database, javaOptions);
    }
}
