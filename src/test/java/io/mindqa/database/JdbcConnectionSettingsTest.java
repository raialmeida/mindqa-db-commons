package io.mindqa.database;

import java.sql.DriverPropertyInfo;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import com.microsoft.sqlserver.jdbc.SQLServerDriver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcConnectionSettingsTest {
    @ParameterizedTest
    @ValueSource(strings = {"qa simples", "qa;encrypt=true;user=outro", "qa{teste}final}", "ação/?#%+"})
    void sqlServerDriverParsesDatabaseAsOneLiteralValue(String database) throws Exception {
        JdbcConnectionSettings settings = settings(Map.of("DB_NAME", database));
        DriverPropertyInfo[] properties = new SQLServerDriver()
                .getPropertyInfo(settings.jdbcUrl(), settings.connectionProperties());
        assertEquals(database, property(properties, "databaseName"));
        assertTrue("false".equalsIgnoreCase(property(properties, "encrypt")));
        assertTrue("false".equalsIgnoreCase(property(properties, "trustServerCertificate")));
        assertEquals("qa_user", property(properties, "user"));
    }

    @Test
    void configuresSqlServerEncryptionWithoutChangingCredentialsOrDatabase() throws Exception {
        JdbcConnectionSettings settings = settings(Map.of(
                "DB_ENCRYPT", "true", "DB_TRUST_SERVER_CERTIFICATE", "true"));
        DriverPropertyInfo[] properties = new SQLServerDriver()
                .getPropertyInfo(settings.jdbcUrl(), settings.connectionProperties());
        assertTrue("true".equalsIgnoreCase(property(properties, "encrypt")));
        assertTrue("true".equalsIgnoreCase(property(properties, "trustServerCertificate")));
        assertEquals("qa_default", property(properties, "databaseName"));
        assertEquals("qa_user", property(properties, "user"));
    }

    @Test
    void supportsSqlServerStrictEncryption() throws Exception {
        JdbcConnectionSettings settings = settings(Map.of("DB_ENCRYPT", "strict"));
        DriverPropertyInfo[] properties = new SQLServerDriver()
                .getPropertyInfo(settings.jdbcUrl(), settings.connectionProperties());
        assertEquals("strict", property(properties, "encrypt").toLowerCase(java.util.Locale.ROOT));
    }

    @Test
    void rejectsInvalidSqlServerEncryption() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> settings(Map.of("DB_ENCRYPT", "enabled")));
        assertTrue(exception.getMessage().contains("DB_ENCRYPT"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sqlserver", "postgres", "oracle", "mysql"})
    void passesAdditionalPropertiesToEveryDriver(String type) {
        JdbcConnectionSettings settings = settings(Map.of("DB_TYPE", type,
                "DB_DRIVER_PROPERTIES", "applicationName=qa%20automation&custom.flag=a%26b%3Dc"));
        Properties properties = settings.connectionProperties();
        assertEquals("qa automation", properties.getProperty("applicationName"));
        assertEquals("a&b=c", properties.getProperty("custom.flag"));
        assertEquals("qa_user", properties.getProperty("user"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "=value", "key=value&", "key=one&key=two",
            "user=other", "password=other", "encrypt=true", "trustServerCertificate=true",
            "loginTimeout=99", "bad%ZZ=value"})
    void rejectsInvalidReservedOrDuplicatedDriverProperties(String value) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> settings(Map.of("DB_DRIVER_PROPERTIES", value)));
        assertTrue(exception.getMessage().contains("DB_DRIVER_PROPERTIES"));
    }

    @Test
    void appliesSqlServerAndAdditionalPropertiesToNamedConnection() throws Exception {
        Map<String, String> environment = Map.of(
                "DB_CONNECTIONS_LEGADO_TYPE", "sqlserver",
                "DB_CONNECTIONS_LEGADO_HOST", "localhost",
                "DB_CONNECTIONS_LEGADO_USER", "qa_user",
                "DB_CONNECTIONS_LEGADO_PASS", "qa_pass",
                "DB_CONNECTIONS_LEGADO_NAME", "qa_database",
                "DB_CONNECTIONS_LEGADO_ENCRYPT", "true",
                "DB_CONNECTIONS_LEGADO_TRUST_SERVER_CERTIFICATE", "true",
                "DB_CONNECTIONS_LEGADO_DRIVER_PROPERTIES", "applicationName=qa-legado");
        DatabaseConfiguration configuration = DatabaseConfigurationLoader
                .load(environment, new Properties(), null).forConnection("legado");
        JdbcConnectionSettings settings = JdbcConnectionSettings.from(configuration, null);
        DriverPropertyInfo[] properties = new SQLServerDriver()
                .getPropertyInfo(settings.jdbcUrl(), settings.connectionProperties());
        assertTrue("true".equalsIgnoreCase(property(properties, "encrypt")));
        assertTrue("true".equalsIgnoreCase(property(properties, "trustServerCertificate")));
        assertEquals("qa-legado", settings.connectionProperties().getProperty("applicationName"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"qa simples", "qa/segundo?user=outro#fragmento", "ação%+&=🧪"})
    void postgresDriverDecodesDatabaseWithoutAddingConnectionProperties(String database) {
        JdbcConnectionSettings settings = settings(Map.of("DB_TYPE", "postgres", "DB_NAME", database));
        Properties properties = org.postgresql.Driver.parseURL(settings.jdbcUrl(), settings.connectionProperties());
        assertNotNull(properties);
        assertEquals(database, properties.getProperty("PGDBNAME"));
        assertEquals("qa_user", properties.getProperty("user"));
        assertFalse(settings.jdbcUrl().contains("?"));
    }

    @ParameterizedTest
    @CsvSource({"sqlserver,::1,1433", "sqlserver,[::1],1433", "postgres,2001:db8::1,5432",
            "postgres,[2001:db8::1],5432", "mysql,::1,3306", "oracle,[::1],1521"})
    void normalizesIpv6Address(String type, String host, String port) {
        JdbcConnectionSettings settings = settings(Map.of("DB_TYPE", type, "DB_HOST", host));
        String bareHost = host.replace("[", "").replace("]", "");
        assertTrue(settings.jdbcUrl().contains("//[" + bareHost + "]:" + port));
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost;user=outro", "localhost/database", "localhost?user=outro",
            "localhost:5432", "host com espaço", "[localhost]", "host\nquebra", "host,second-host",
            "host)(PORT=1234", "host\""})
    void rejectsHostWithJdbcPropertiesOrPort(String host) {
        assertThrows(IllegalArgumentException.class, () -> settings(Map.of("DB_HOST", host)));
    }

    @ParameterizedTest
    @CsvSource({"DB_QUERY_TIMEOUT_SECONDS,-1", "DB_QUERY_TIMEOUT_SECONDS,abc",
            "DB_LOGIN_TIMEOUT_SECONDS,-1", "DB_LOGIN_TIMEOUT_SECONDS,65536", "DB_LOGIN_TIMEOUT_SECONDS,2147483648"})
    void validatesTimeouts(String key, String value) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> settings(Map.of(key, value)));
        assertTrue(exception.getMessage().contains(key));
    }

    @Test
    void connectionPropertiesAreIndependentAndDoNotChangeGlobalTimeout() {
        int globalTimeout = java.sql.DriverManager.getLoginTimeout();
        JdbcConnectionSettings settings = settings(Map.of("DB_QUERY_TIMEOUT_SECONDS", "12", "DB_LOGIN_TIMEOUT_SECONDS", "7"));
        Properties first = settings.connectionProperties();
        first.setProperty("password", "alterada");
        assertEquals(" senha com espaços ", settings.connectionProperties().getProperty("password"));
        assertEquals("7", settings.connectionProperties().getProperty("loginTimeout"));
        assertEquals(12, settings.queryTimeoutSeconds());
        assertEquals(globalTimeout, java.sql.DriverManager.getLoginTimeout());
        assertFalse(settings.jdbcUrl().contains("senha"));
    }

    @Test
    void blankTimeoutsKeepDriverDefaults() {
        JdbcConnectionSettings settings = settings(Map.of("DB_QUERY_TIMEOUT_SECONDS", " ", "DB_LOGIN_TIMEOUT_SECONDS", "0"));
        assertEquals(0, settings.queryTimeoutSeconds());
        assertFalse(settings.connectionProperties().containsKey("loginTimeout"));
    }

    @Test
    void rejectsControlCharactersInDatabaseName() {
        assertThrows(IllegalArgumentException.class, () -> settings(Map.of("DB_NAME", "qa\nforjado")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"qa simples", "qa/second?user=other#fragment", "ação%+&=🧪"})
    void mysqlDriverPreservesDatabaseAndSeparateCredentials(String database) {
        JdbcConnectionSettings settings = settings(Map.of("DB_TYPE", "mysql", "DB_NAME", database));
        com.mysql.cj.conf.ConnectionUrl parsed = com.mysql.cj.conf.ConnectionUrl
                .getConnectionUrlInstance(settings.jdbcUrl(), settings.connectionProperties());
        assertEquals(database, parsed.getMainHost().getDatabase());
        assertEquals("qa_user", parsed.getMainHost().getUser());
        assertEquals(" senha com espaços ", parsed.getMainHost().getPassword());
        assertEquals(3306, parsed.getMainHost().getPort());
        assertEquals(1, parsed.getHostsList().size());
    }

    @Test
    void oracleUsesServiceNameAndDriverRecognizesUrl() throws Exception {
        JdbcConnectionSettings settings = settings(Map.of("DB_TYPE", " ORACLE ", "DB_NAME", "FREEPDB1.example.com"));
        assertEquals("jdbc:oracle:thin:@//localhost:1521/FREEPDB1.example.com", settings.jdbcUrl());
        assertTrue(new oracle.jdbc.OracleDriver().acceptsURL(settings.jdbcUrl()));
        assertEquals("qa_user", settings.connectionProperties().getProperty("user"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"service?user=other", "service/other", "service:dedicated", "(DESCRIPTION=evil)", "service name"})
    void rejectsOracleServiceNamesThatCouldAlterConnectionDescriptor(String service) {
        assertThrows(IllegalArgumentException.class, () -> settings(Map.of("DB_TYPE", "oracle", "DB_NAME", service)));
    }

    @ParameterizedTest
    @CsvSource({"oracle,oracle.jdbc.loginTimeout,7", "mysql,connectTimeout,7000", "postgres,loginTimeout,7",
            "sqlserver,loginTimeout,7"})
    void translatesTimeoutToEachDriversPropertyAndUnit(String type, String property, String value) {
        int globalTimeout = java.sql.DriverManager.getLoginTimeout();
        JdbcConnectionSettings settings = settings(Map.of("DB_TYPE", type, "DB_LOGIN_TIMEOUT_SECONDS", "7"));
        assertEquals(value, settings.connectionProperties().getProperty(property));
        assertEquals(3, settings.connectionProperties().size());
        assertEquals(globalTimeout, java.sql.DriverManager.getLoginTimeout());
    }

    @Test
    void mysqlMaximumTimeoutDoesNotOverflowMilliseconds() {
        assertEquals("65535000", settings(Map.of("DB_TYPE", "mysql", "DB_LOGIN_TIMEOUT_SECONDS", "65535"))
                .connectionProperties().getProperty("connectTimeout"));
    }

    private JdbcConnectionSettings settings(Map<String, String> overrides) {
        Map<String, String> environment = new HashMap<>(Map.of("DB_HOST", "localhost",
                "DB_USER", "qa_user", "DB_PASS", " senha com espaços ", "DB_NAME", "qa_default"));
        environment.putAll(overrides);
        return JdbcConnectionSettings.from(DatabaseConfigurationLoader.load(environment, new Properties(), null), null);
    }

    private String property(DriverPropertyInfo[] properties, String key) {
        return Arrays.stream(properties).filter(property -> key.equals(property.name))
                .findFirst().orElseThrow().value;
    }
}
