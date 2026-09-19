package br.com.mindqa.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLTransientConnectionException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcConnectionPoolsTest {
    @ParameterizedTest
    @CsvSource({"DB_POOL_ENABLED,yes", "DB_POOL_MAX_SIZE,0", "DB_POOL_MAX_SIZE,abc",
        "DB_POOL_CONNECTION_TIMEOUT_MS,999", "DB_POOL_CONNECTION_TIMEOUT_MS,2147483648"})
    void rejectsInvalidPoolSettings(String key, String value) {
        assertThrows(IllegalArgumentException.class, () -> settings(Map.of(key, value), null));
    }

    @Test
    void disabledPoolIgnoresUnusedLimits() {
        JdbcConnectionSettings configuration = settings(Map.of("DB_POOL_ENABLED", "false",
                "DB_POOL_MAX_SIZE", "invalid", "DB_POOL_CONNECTION_TIMEOUT_MS", "invalid"), null);
        assertFalse(configuration.poolEnabled());
    }

    private final List<Connection> opened = new CopyOnWriteArrayList<>();
    private final JdbcConnectionPools pools = new JdbcConnectionPools(this::connect);

    @AfterEach
    void closePools() throws SQLException {
        pools.close();
        for (Connection connection : opened) {
            assertTrue(connection.isClosed());
        }
    }

    @Test
    void sequentialBorrowsReuseOnePhysicalConnectionAndResetAutoCommit() throws Exception {
        JdbcConnectionSettings settings = settings(Map.of(), null);
        Connection physical = null;
        for (int i = 0; i < 20; i++) {
            try (Connection connection = pools.borrow(settings)) {
                assertTrue(connection.getAutoCommit());
                Connection current = connection.unwrap(org.h2.jdbc.JdbcConnection.class);
                if (physical != null) {
                    assertSame(physical, current);
                }
                physical = current;
                connection.setAutoCommit(false);
            }
        }
        assertEquals(1, opened.size());
        assertFalse(opened.get(0).isClosed());
    }

    @ParameterizedTest
    @CsvSource({"DB_NAME,outra", "DB_HOST,outro-servidor", "DB_USER,outro-usuario",
            "DB_PASS,outro-segredo", "DB_PORT,5440", "DB_LOGIN_TIMEOUT_SECONDS,4", "DB_TYPE,mysql"})
    void isolatesChangesToDestinationAndCredentials(String key, String value) throws Exception {
        Connection first;
        try (Connection connection = pools.borrow(settings(Map.of(), null))) {
            first = connection.unwrap(org.h2.jdbc.JdbcConnection.class);
        }
        try (Connection connection = pools.borrow(settings(Map.of(key, value), null))) {
            assertNotSame(first, connection.unwrap(org.h2.jdbc.JdbcConnection.class));
        }
        assertEquals(2, opened.size());
    }

    @Test
    void isolatesNamesAndDatabaseOverridesWhileReusingDefaultAndExplicitName() throws Exception {
        Properties properties = new Properties();
        for (String name : List.of("principal", "replica")) {
            defaults().forEach((key, value) -> properties.setProperty(
                    "DB_CONNECTIONS_" + name.toUpperCase(java.util.Locale.ROOT) + "_" + key.substring(3), value));
        }
        properties.setProperty("DB_DEFAULT_CONNECTION", "principal");
        DatabaseConfiguration configuration = new DatabaseConfiguration(properties, Map.of());
        Connection first;
        try (Connection connection = pools.borrow(JdbcConnectionSettings.from(configuration.forConnection(null), null))) {
            first = connection.unwrap(org.h2.jdbc.JdbcConnection.class);
        }
        try (Connection connection = pools.borrow(JdbcConnectionSettings.from(configuration.forConnection("principal"), null))) {
            assertSame(first, connection.unwrap(org.h2.jdbc.JdbcConnection.class));
        }
        try (Connection connection = pools.borrow(JdbcConnectionSettings.from(configuration.forConnection("replica"), null))) {
            assertNotSame(first, connection.unwrap(org.h2.jdbc.JdbcConnection.class));
        }
        try (Connection connection = pools.borrow(JdbcConnectionSettings.from(configuration.forConnection("principal"), "auditoria"))) {
            assertNotSame(first, connection.unwrap(org.h2.jdbc.JdbcConnection.class));
        }
        assertEquals(3, opened.size());
    }

    @Test
    void exhaustedPoolTimesOutAndBecomesAvailableAfterReturn() throws Exception {
        JdbcConnectionSettings settings = settings(Map.of(), null);
        Connection physical;
        try (Connection held = pools.borrow(settings)) {
            physical = held.unwrap(org.h2.jdbc.JdbcConnection.class);
            assertThrows(SQLTransientConnectionException.class, () -> pools.borrow(settings));
            assertEquals(1, opened.size());
        }
        try (Connection available = pools.borrow(settings)) {
            assertSame(physical, available.unwrap(org.h2.jdbc.JdbcConnection.class));
        }
    }

    @Test
    void concurrentBorrowWaitsForReturnWithoutExceedingLimit() throws Exception {
        JdbcConnectionSettings settings = settings(Map.of("DB_POOL_CONNECTION_TIMEOUT_MS", "5000"), null);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Connection first = pools.borrow(settings);
        try {
            CountDownLatch attempting = new CountDownLatch(1);
            Future<Boolean> waiter = executor.submit(() -> {
                attempting.countDown();
                try (Connection connection = pools.borrow(settings)) {
                    return connection.isValid(1);
                }
            });
            assertTrue(attempting.await(5, TimeUnit.SECONDS));
            first.close();
            assertTrue(waiter.get(5, TimeUnit.SECONDS));
            assertEquals(1, opened.size());
        } finally {
            first.close();
            executor.shutdownNow();
        }
    }

    @Test
    void propagatesOriginalDriverAuthenticationFailure() {
        SQLException original = new SQLInvalidAuthorizationSpecException("Credenciais recusadas", "28000", 123);
        try (JdbcConnectionPools failing = new JdbcConnectionPools((url, properties) -> { throw original; })) {
            assertSame(original, assertThrows(SQLException.class, () -> failing.borrow(settings(Map.of(), null))));
        }
    }

    @Test
    void closeReleasesConnectionsAndAllowsNewPoolLater() throws Exception {
        try (Connection connection = pools.borrow(settings(Map.of(), null))) {
            assertTrue(connection.isValid(1));
        }
        pools.close();
        assertTrue(opened.get(0).isClosed());
        try (Connection connection = pools.borrow(settings(Map.of(), null))) {
            assertTrue(connection.isValid(1));
        }
        assertEquals(2, opened.size());
    }

    @Test
    void excessDestinationsUseDirectConnectionsWithoutUnboundedPools() throws Exception {
        for (int i = 0; i < 32; i++) {
            try (Connection connection = pools.borrow(settings(Map.of(), "base" + i))) {
                assertTrue(connection.isValid(1));
            }
        }
        assertEquals(32, opened.size());
        try (Connection connection = pools.borrow(settings(Map.of(), "base-extra"))) {
            assertSame(opened.get(32), connection);
        }
        assertTrue(opened.get(32).isClosed());
    }

    private Connection connect(String url, Properties properties) throws SQLException {
        Connection connection = new org.h2.Driver().connect("jdbc:h2:mem:" + UUID.randomUUID(), new Properties());
        opened.add(connection);
        return connection;
    }

    private static JdbcConnectionSettings settings(Map<String, String> overrides, String database) {
        Map<String, String> values = defaults();
        values.putAll(overrides);
        return JdbcConnectionSettings.from(new DatabaseConfiguration(new Properties(), values), database);
    }

    private static Map<String, String> defaults() {
        return new HashMap<>(Map.of("DB_TYPE", "postgres", "DB_HOST", "localhost", "DB_USER", "qa",
                "DB_PASS", "test", "DB_NAME", "qa", "DB_POOL_ENABLED", "true", "DB_POOL_MAX_SIZE", "1",
                "DB_POOL_CONNECTION_TIMEOUT_MS", "1000"));
    }
}
