package io.mindqa.database;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.logging.Logger;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/** Pools sob demanda, isolados por destino e credenciais, com limite de retenção. */
final class JdbcConnectionPools implements AutoCloseable {
    private static final int MAX_POOLS = 32;
    private final Map<Key, HikariDataSource> pools = new HashMap<>();
    private final ConnectionFactory connections;

    @FunctionalInterface
    interface ConnectionFactory {
        Connection open(String url, Properties properties) throws SQLException;
    }

    JdbcConnectionPools(ConnectionFactory connections) {
        this.connections = connections;
    }

    private static final class Shared {
        private static final JdbcConnectionPools INSTANCE = new JdbcConnectionPools(DriverManager::getConnection);

        static {
            Runtime.getRuntime().addShutdownHook(new Thread(INSTANCE::close, "mindqa-database-shutdown"));
        }
    }

    static Connection open(JdbcConnectionSettings settings) throws SQLException {
        return settings.poolEnabled() ? Shared.INSTANCE.borrow(settings)
                : DriverManager.getConnection(settings.jdbcUrl(), settings.connectionProperties());
    }

    static void closeShared() {
        Shared.INSTANCE.close();
    }

    Connection borrow(JdbcConnectionSettings settings) throws SQLException {
        Key key = new Key(settings, Thread.currentThread().getContextClassLoader());
        HikariDataSource pool;
        synchronized (pools) {
            pool = pools.get(key);
            if (pool == null && pools.size() < MAX_POOLS) {
                // A inicialização física é adiada até getConnection(), fora deste lock.
                pool = create(settings);
                pools.put(key, pool);
            }
        }
        if (pool == null) {
            // Muitos destinos dinâmicos não devem criar pools e threads sem limite.
            return connections.open(settings.jdbcUrl(), settings.connectionProperties());
        }
        try {
            return pool.getConnection();
        } catch (SQLException exception) {
            // Hikari pode anexar a falha de autenticação/conexão ao timeout de aquisição.
            // Preserve a instância JDBC do driver quando ela estiver disponível.
            for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException) {
                    throw (SQLException) cause;
                }
            }
            throw exception;
        }
    }

    private HikariDataSource create(JdbcConnectionSettings settings) {
        HikariConfig config = new HikariConfig();
        config.setDataSource(new ConnectionSource(settings, connections));
        config.setMaximumPoolSize(settings.poolMaxSize());
        config.setMinimumIdle(0);
        config.setConnectionTimeout(settings.poolConnectionTimeoutMs());
        config.setValidationTimeout(Math.min(5000, settings.poolConnectionTimeoutMs() - 1));
        config.setIdleTimeout(60000);
        config.setKeepaliveTime(0);
        config.setAutoCommit(true);
        config.setInitializationFailTimeout(-1);
        HikariDataSource pool = new HikariDataSource();
        config.copyStateTo(pool);
        return pool;
    }

    @Override
    public void close() {
        List<HikariDataSource> closing;
        synchronized (pools) {
            closing = new ArrayList<>(pools.values());
            pools.clear();
        }
        closing.forEach(HikariDataSource::close);
    }

    /** Nunca imprime a chave: contém credenciais usadas apenas para identificar o destino. */
    private static final class Key {
        private final String name;
        private final String url;
        private final Properties properties;
        private final int maxSize;
        private final int timeout;
        private final ClassLoader loader;

        private Key(JdbcConnectionSettings settings, ClassLoader loader) {
            this.name = settings.connectionName();
            this.url = settings.jdbcUrl();
            this.properties = settings.connectionProperties();
            this.maxSize = settings.poolMaxSize();
            this.timeout = settings.poolConnectionTimeoutMs();
            this.loader = loader;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Key)) {
                return false;
            }
            Key key = (Key) other;
            return Objects.equals(name, key.name) && url.equals(key.url) && properties.equals(key.properties)
                    && maxSize == key.maxSize && timeout == key.timeout && loader == key.loader;
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, url, properties, maxSize, timeout, System.identityHashCode(loader));
        }
    }

    /** Adapta DriverManager sem alterar o login timeout global nem expor credenciais em logs do pool. */
    private static final class ConnectionSource implements DataSource {
        private final JdbcConnectionSettings settings;
        private final ConnectionFactory connections;

        private ConnectionSource(JdbcConnectionSettings settings, ConnectionFactory connections) {
            this.settings = settings;
            this.connections = connections;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return connections.open(settings.jdbcUrl(), settings.connectionProperties());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            Properties properties = settings.connectionProperties();
            properties.setProperty("user", username);
            properties.setProperty("password", password);
            return connections.open(settings.jdbcUrl(), properties);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter writer) throws SQLException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public void setLoginTimeout(int seconds) {
            // O timeout por driver já está em connectionProperties(); não usar DriverManager.setLoginTimeout.
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public boolean isWrapperFor(Class<?> type) {
            return type.isInstance(this);
        }

        @Override
        public <T> T unwrap(Class<T> type) throws SQLException {
            if (isWrapperFor(type)) {
                return type.cast(this);
            }
            throw new SQLException("DataSource não implementa o tipo solicitado.");
        }
    }
}
