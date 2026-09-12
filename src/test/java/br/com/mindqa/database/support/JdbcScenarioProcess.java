package br.com.mindqa.database.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import br.com.mindqa.database.DatabaseException;
import br.com.mindqa.database.DatabaseService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Executado em outra JVM para testar System.getenv sem reflexão ou estado global compartilhado. */
public final class JdbcScenarioProcess {
    private static RecordingJdbcDriver driver;
    private static String database;
    private static boolean useDatabaseOverride;

    private JdbcScenarioProcess() {
    }

    public static void main(String[] args) throws Exception {
        String action = args[0];
        useDatabaseOverride = !"-".equals(args[2]);
        database = "<null>".equals(args[2]) ? null : args[2];

        // Drivers reais nunca são usados para abrir conexões durante estes testes.
        for (Driver registered : Collections.list(DriverManager.getDrivers())) {
            DriverManager.deregisterDriver(registered);
        }
        driver = new RecordingJdbcDriver(args[1], action);
        DriverManager.registerDriver(driver);

        try {
            switch (action) {
                case "crud":
                    verifyCrud();
                    break;
                case "query-error":
                case "update-error":
                case "connection-error":
                case "changed-config-error":
                    verifySqlFailure(action);
                    break;
                case "statement-error":
                case "statement-and-close-error":
                case "close-error":
                    verifyInjectedFailure(action);
                    break;
                case "parallel":
                    verifyConcurrentCalls();
                    break;
                case "invalid-config":
                    verifyConfigurationFailure(IllegalArgumentException.class, args[1]);
                    break;
                case "missing-config":
                case "file-error":
                    verifyConfigurationFailure(IllegalStateException.class, args[1]);
                    break;
                default:
                    throw new AssertionError("Cenário desconhecido: " + action);
            }
        } finally {
            driver.assertConnectionsClosed();
            DriverManager.deregisterDriver(driver);
        }
    }

    private static void verifyCrud() throws SQLException {
        update("CREATE TABLE clientes (id INTEGER PRIMARY KEY, nome VARCHAR(100), email VARCHAR(150))");
        assertTrue(query("SELECT * FROM clientes").isEmpty());

        String name = "D'Ávila; SELECT 1 --";
        assertEquals(1, update("INSERT INTO clientes (id, nome, email) VALUES (?, ?, ?)",
                1, name, "qa@example.com"));
        assertEquals(1, update("INSERT INTO clientes (id, nome, email) VALUES (?, ?, ?)",
                2, "Segundo cliente", null));

        List<Map<String, Object>> rows = query("SELECT id, nome AS cliente, email FROM clientes ORDER BY id");
        assertEquals(2, rows.size());
        assertEquals(1, ((Number) rows.get(0).get("id")).intValue());
        assertEquals(name, rows.get(0).get("cliente"));
        assertNull(rows.get(1).get("email"));
        assertEquals(1, query("SELECT id FROM clientes WHERE nome = ?", name).size());
        assertTrue(query("SELECT id FROM clientes WHERE nome = ?", "' OR 1=1 --").isEmpty());

        assertEquals(1, update("UPDATE clientes SET nome = ? WHERE id = ?", "Atualizado", 1));
        assertEquals("Atualizado", query("SELECT nome FROM clientes WHERE id = ?", 1).get(0).get("nome"));
        assertEquals(1, update("UPDATE clientes SET email = ? WHERE id = 1", (Object) null));
        assertNull(query("SELECT email FROM clientes WHERE id = ?", 1).get(0).get("email"));
        assertEquals(0, update("DELETE FROM clientes WHERE id = ?", 999));
        assertEquals(1, update("DELETE FROM clientes WHERE id = ?", 1));
        assertEquals(1, update("DELETE FROM clientes WHERE id = ?", 2));
        assertTrue(query("SELECT * FROM clientes").isEmpty());
        assertFalse(driver.connections.isEmpty());
    }

    private static void verifySqlFailure(String action) {
        boolean update = "update-error".equals(action);
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            if (update) {
                update("UPDATE tabela_inexistente SET nome = ?", "novo");
            } else {
                query("SELECT * FROM tabela_inexistente WHERE id = ?", 1);
            }
        });
        assertInstanceOf(SQLException.class, exception.getCause());
        assertTrue(exception.getMessage().contains(update ? "INSERT/UPDATE/DELETE" : "SELECT"));
        assertTrue(exception.getMessage().contains("qa_default"));
        assertEquals(1, driver.attempts.get());
        if ("connection-error".equals(action) || "changed-config-error".equals(action)) {
            assertEquals("08001", ((SQLException) exception.getCause()).getSQLState());
        } else {
            assertEquals(1, driver.connections.size());
        }
    }

    private static void verifyConfigurationFailure(Class<? extends RuntimeException> type, String variable) {
        RuntimeException exception = assertThrows(type, () -> query("SELECT 1"));
        assertTrue(exception.getMessage().contains(variable));
        assertEquals(0, driver.attempts.get());
    }

    private static void verifyInjectedFailure(String action) {
        DatabaseException exception = assertThrows(DatabaseException.class,
                () -> query("SELECT ? AS valor", "parametro-confidencial"));
        SQLException expected = "close-error".equals(action) ? driver.closeFailure : driver.statementFailure;
        assertSame(expected, exception.getCause());
        assertEquals(expected.getSQLState(), exception.getSqlState());
        assertEquals(expected.getErrorCode(), exception.getErrorCode());
        assertEquals("SELECT", exception.getOperation());
        assertEquals("qa_default", exception.getDatabase());
        assertFalse(exception.getMessage().contains("parametro-confidencial"));
        assertFalse(exception.getCause().getMessage().contains("parametro-confidencial"));
        if ("statement-and-close-error".equals(action)) {
            assertEquals(1, expected.getSuppressed().length);
            assertSame(driver.closeFailure, expected.getSuppressed()[0]);
        }
    }

    private static void verifyConcurrentCalls() throws Exception {
        DatabaseService.executeUpdate("CREATE TABLE paralelo (id INTEGER PRIMARY KEY, nome VARCHAR(100))");
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> calls = new ArrayList<>();
            for (int id = 1; id <= 20; id++) {
                final int identifier = id;
                calls.add(executor.submit(() -> {
                    assertEquals(1, DatabaseService.executeUpdate(
                            "INSERT INTO paralelo (id, nome) VALUES (?, ?)", identifier, "cliente-" + identifier));
                    assertEquals("cliente-" + identifier, DatabaseService.select(
                            "SELECT nome FROM paralelo WHERE id = ?", identifier).get(0).get("nome"));
                }));
            }
            for (Future<?> call : calls) {
                call.get(15, TimeUnit.SECONDS);
            }
            assertEquals(41, driver.attempts.get());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static List<Map<String, Object>> query(String sql, Object... params) throws SQLException {
        try {
            return useDatabaseOverride ? DatabaseService.selectInDb(database, sql, params)
                    : DatabaseService.select(sql, params);
        } finally {
            driver.assertConnectionsClosed();
        }
    }

    private static int update(String sql, Object... params) throws SQLException {
        try {
            return useDatabaseOverride ? DatabaseService.executeUpdateInDb(database, sql, params)
                    : DatabaseService.executeUpdate(sql, params);
        } finally {
            driver.assertConnectionsClosed();
        }
    }

    private static final class RecordingJdbcDriver implements Driver {
        private final String expectedUrl;
        private final String action;
        private final List<Connection> connections = new CopyOnWriteArrayList<>();
        private final List<PreparedStatement> statements = new CopyOnWriteArrayList<>();
        private final AtomicInteger attempts = new AtomicInteger();
        private final SQLException statementFailure = new SQLException("Falha simulada no statement", "42000", 1234);
        private final SQLException closeFailure = new SQLException("Falha simulada ao fechar conexão", "08006", 5678);

        private RecordingJdbcDriver(String expectedUrl, String action) {
            this.expectedUrl = expectedUrl;
            this.action = action;
        }

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            if (!acceptsURL(url)) {
                return null;
            }
            attempts.incrementAndGet();
            assertEquals(expectedUrl, url);
            assertEquals("qa_user", info.getProperty("user"));
            assertEquals(System.getProperty("scenario.password", System.getenv("DB_PASS")),
                    info.getProperty("password"));
            assertEquals(System.getProperty("scenario.loginTimeout"), info.getProperty("loginTimeout"));
            if ("changed-config-error".equals(action)) {
                try {
                    Files.writeString(Path.of(System.getProperty("scenario.replace.config")), "DB_NAME=alterado\n");
                } catch (java.io.IOException exception) {
                    throw new AssertionError(exception);
                }
            }
            if ("connection-error".equals(action) || "changed-config-error".equals(action)) {
                throw new SQLException("Falha de conexão simulada", "08001");
            }
            Connection connection = new org.h2.Driver().connect(
                    "jdbc:h2:mem:qa;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", new Properties());
            assertTrue(connection.getAutoCommit());
            connections.add(connection);
            return (Connection) Proxy.newProxyInstance(JdbcScenarioProcess.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                        Object result = invoke(connection, method, arguments);
                        if ("close".equals(method.getName()) && action.endsWith("close-error")) {
                            throw closeFailure;
                        }
                        if (result instanceof PreparedStatement) {
                            PreparedStatement statement = (PreparedStatement) result;
                            statements.add(statement);
                            return trackStatement(statement);
                        }
                        return result;
                    });
        }

        private PreparedStatement trackStatement(PreparedStatement statement) {
            return (PreparedStatement) Proxy.newProxyInstance(JdbcScenarioProcess.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, arguments) -> {
                        if ("executeQuery".equals(method.getName()) || "executeUpdate".equals(method.getName())) {
                            assertEquals(Integer.parseInt(System.getProperty("scenario.queryTimeout", "0")),
                                    statement.getQueryTimeout());
                            if (action.startsWith("statement-")) {
                                throw statementFailure;
                            }
                        }
                        return invoke(statement, method, arguments);
                    });
        }

        private Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
            try {
                return method.invoke(target, arguments);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }

        private void assertConnectionsClosed() throws SQLException {
            for (Connection connection : connections) {
                assertTrue(connection.isClosed(), "A conexão deve ser fechada após cada operação.");
            }
            for (PreparedStatement statement : statements) {
                assertTrue(statement.isClosed(), "O statement deve ser fechado após cada operação.");
            }
        }

        @Override
        public boolean acceptsURL(String url) {
            return url != null && (url.startsWith("jdbc:sqlserver:") || url.startsWith("jdbc:postgresql:"));
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 1;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getLogger("br.com.mindqa.database.test");
        }
    }
}
