package br.com.mindqa.database;

import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = {"SQLSERVER", "POSTGRESQL", "MYSQL", "ORACLE"})
    void enginePrefixesInferTypeWithoutTypeProperty(String prefix) {
        DatabaseConfiguration configuration = configuration(Map.of(prefix + "_HOST", "localhost",
                prefix + "_USER", "qa_user", prefix + "_PASS", "secret", prefix + "_NAME", "qa_database"), Map.of());
        DatabaseConfiguration selected = configuration.forConnection(null);
        assertEquals(prefix.toLowerCase(java.util.Locale.ROOT), selected.get("DB_TYPE"));
        assertEquals("localhost", selected.get("DB_HOST"));
        assertEquals("qa_database", selected.get("DB_NAME"));
        assertEquals("secret", configuration.forConnection(prefix).get("DB_PASS"));
    }

    @Test
    void namedEnginePrefixesDoNotDuplicateConnectionName() {
        DatabaseConfiguration selected = configuration(Map.of("oracle.erp.host", "oracle.test",
                "oracle.erp.name", "FREEPDB1"), Map.of("ORACLE_ERP_USER", "erp_user"))
                .forConnection("erp");
        assertEquals("oracle", selected.get("DB_TYPE"));
        assertEquals("oracle.test", selected.get("DB_HOST"));
        assertEquals("erp_user", selected.get("DB_USER"));
        assertEquals("ORACLE_ERP_PASS", selected.key("DB_PASS"));
    }

    @Test
    void typedPrefixesRespectGlobalDefaultAndAmbiguityRules() {
        Map<String, String> values = Map.of("ORACLE_HOST", "oracle.test", "MYSQL_HOST", "mysql.test");
        assertThrows(IllegalStateException.class, () -> configuration(values, Map.of()).forConnection(null));
        DatabaseConfiguration selected = configuration(values, Map.of("DB_DEFAULT_CONNECTION", "mysql")).forConnection(null);
        assertEquals("mysql", selected.get("DB_TYPE"));
        assertEquals("mysql.test", selected.get("DB_HOST"));
    }

    @Test
    void typedAliasesRespectSourcePriorityAndCanonicalKeysWithinSameSource() {
        DatabaseConfiguration configuration = configuration(Map.of("ORACLE_ERP_HOST", "alias-file",
                "DB_CONNECTIONS_ERP_HOST", "canonical-file", "ORACLE_ERP_PASS", "file-secret"),
                Map.of("ORACLE_ERP_HOST", "alias-env", "ORACLE_ERP_PASS", ""));
        assertEquals("alias-env", configuration.forConnection("erp").get("DB_HOST"));
        assertEquals("", configuration.forConnection("erp").get("DB_PASS"));
        assertEquals("canonical-file", configuration(Map.of("ORACLE_ERP_HOST", "alias-file",
                "DB_CONNECTIONS_ERP_HOST", "canonical-file"), Map.of()).forConnection("erp").get("DB_HOST"));
    }

    @Test
    void rejectsConflictingPrefixesForSameNameAndContradictingExplicitType() {
        assertThrows(IllegalStateException.class, () -> configuration(Map.of("ORACLE_ERP_HOST", "oracle.test",
                "MYSQL_ERP_HOST", "mysql.test"), Map.of()).forConnection("erp"));
        DatabaseConfiguration configuration = configuration(Map.of("ORACLE_HOST", "localhost", "ORACLE_TYPE", "mysql"), Map.of());
        assertThrows(IllegalArgumentException.class, () -> configuration.forConnection("oracle").get("DB_TYPE"));
    }

    @Test
    void namedSourcesRespectEnvironmentAndUppercasePrecedence() {
        DatabaseConfiguration configuration = configuration(Map.of(
                "db.connections.erp.type", "oracle", "db.connections.erp.pass", "lowercase",
                "DB_CONNECTIONS_ERP_PASS", "uppercase", "db.connections.erp.host", "file-host"),
                Map.of("DB_CONNECTIONS_ERP_PASS", "", "DB_CONNECTIONS_ERP_HOST", "env-host"));
        DatabaseConfiguration selected = configuration.forConnection(" ERP ");
        assertEquals("oracle", selected.get("DB_TYPE"));
        assertEquals("", selected.get("DB_PASS"));
        assertEquals("env-host", selected.get("DB_HOST"));
        assertEquals("uppercase", configuration(Map.of("db.connections.erp.pass", "lowercase",
                "DB_CONNECTIONS_ERP_PASS", "uppercase"), Map.of()).forConnection("erp").get("DB_PASS"));
    }

    @Test
    void explicitConnectionOverridesDefaultAndDoesNotInheritOtherCredentials() {
        DatabaseConfiguration configuration = configuration(Map.of("db.default.connection", "main",
                "db.connections.main.type", "postgres", "db.connections.main.pass", "main-secret",
                "db.connections.erp.type", "oracle"), Map.of("DB_PASS", "global-secret"));
        DatabaseConfiguration selected = configuration.forConnection("erp");
        assertEquals("oracle", selected.get("DB_TYPE"));
        assertNull(selected.get("DB_PASS"));
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> selected.required("DB_USER"));
        assertTrue(exception.getMessage().contains("DB_CONNECTIONS_ERP_USER"));
    }

    @Test
    void defaultConnectionEnvironmentOverridesFileAndLegacyConfiguration() {
        DatabaseConfiguration configuration = configuration(Map.of("db.default.connection", "main",
                "db.connections.main.type", "postgres", "db.connections.erp.type", "oracle"),
                Map.of("DB_DEFAULT_CONNECTION", "erp", "DB_TYPE", "sqlserver"));
        assertEquals("oracle", configuration.forConnection(null).get("DB_TYPE"));
        assertEquals("postgres", configuration.forConnection("main").get("DB_TYPE"));
    }

    @Test
    void singleNamedConnectionIsDiscoveredFromEnvironmentOrFile() {
        assertEquals("oracle", configuration(Map.of(), Map.of("DB_CONNECTIONS_ERP2_TYPE", "oracle"))
                .forConnection(null).get("DB_TYPE"));
        assertEquals("mysql", configuration(Map.of("db.connections.shop.type", "mysql"), Map.of())
                .forConnection(null).get("DB_TYPE"));
        assertEquals("mysql", configuration(Map.of("DB_CONNECTIONS_SHOP_TYPE", "mysql",
                "db.connections.shop.host", "localhost"), Map.of()).forConnection(null).get("DB_TYPE"));
    }

    @Test
    void legacyConnectionRemainsDefaultAndIncompleteLegacyConfigIsNotSilentlyReplaced() {
        DatabaseConfiguration configuration = configuration(Map.of("db.connections.erp.type", "oracle"),
                Map.of("DB_USER", "legacy"));
        assertSame(configuration, configuration.forConnection(null));
        assertNull(configuration.forConnection(null).get("DB_HOST"));
    }

    @Test
    void multipleConnectionsNeedExplicitChoiceOrDefault() {
        DatabaseConfiguration configuration = configuration(Map.of("db.connections.one.type", "oracle",
                "db.connections.two.type", "mysql"), Map.of());
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> configuration.forConnection(null));
        assertTrue(exception.getMessage().contains("DB_DEFAULT_CONNECTION"));
        assertEquals("mysql", configuration.forConnection("two").get("DB_TYPE"));
    }

    @Test
    void invalidDefaultDoesNotFallBackToAWorkingConnection() {
        DatabaseConfiguration configuration = configuration(Map.of("db.default.connection", "typo"), Map.of("DB_TYPE", "mysql"));
        assertThrows(IllegalStateException.class, () -> configuration.forConnection(null));
        assertThrows(IllegalArgumentException.class, () -> configuration.forConnection("bad-name"));
    }

    @Test
    void emptyDefaultEnvironmentOverridesFileAndKeepsLegacyDefault() {
        DatabaseConfiguration configuration = configuration(Map.of("db.default.connection", "missing"),
                Map.of("DB_DEFAULT_CONNECTION", " ", "DB_TYPE", "mysql"));
        assertSame(configuration, configuration.forConnection(null));
    }

    @Test
    void unrelatedPropertiesAndIncompletePrefixesDoNotDeclareConnections() {
        DatabaseConfiguration configuration = configuration(Map.of("db.connections.type", "ignored",
                "DB_CONNECTIONS_TYPE", "ignored", "db.connections.app.unrelated", "ignored"), Map.of());
        assertSame(configuration, configuration.forConnection(null));
    }

    private DatabaseConfiguration configuration(Map<String, String> values, Map<String, String> environment) {
        Properties properties = new Properties();
        properties.putAll(values);
        return new DatabaseConfiguration(properties, environment);
    }
}
