package br.com.mindqa.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.apache.commons.dbutils.QueryRunner;
import org.apache.commons.dbutils.StatementConfiguration;
import org.apache.commons.dbutils.handlers.MapListHandler;

/**
 * Operações JDBC para uma conexão selecionada por {@link DatabaseService#connection(String)}.
 * <p>Cada chamada usa uma configuração imutável e sua própria conexão em auto-commit.
 * Este cliente guarda somente o nome da conexão e pode ser reutilizado. Criá-lo não abre
 * conexões JDBC; a configuração é lida a cada operação. Não requer fechamento pelo consumidor.
 * Chamadas concorrentes não compartilham conexões ou estado mutável.
 * </p>
 */
public final class DatabaseClient {
    private final String connectionName;

    DatabaseClient(String connectionName) {
        this.connectionName = connectionName;
    }

    /**
     * Consulta o banco definido na configuração desta conexão.
     *
     * @param sql SQL com placeholders {@code ?} para valores
     * @param params valores na ordem dos placeholders; use {@code (Object) null} para SQL NULL
     * @return linhas indexadas pelo nome ou alias das colunas, ou lista vazia
     * @throws IllegalArgumentException se o SQL estiver vazio, o array de parâmetros for nulo
     *                                  ou a configuração for inválida
     * @throws IllegalStateException se faltar configuração obrigatória ou o arquivo não puder ser lido
     * @throws SQLException erro original do driver ao conectar, consultar ou fechar a conexão
     */
    public List<Map<String, Object>> select(String sql, Object... params) throws SQLException {
        return selectInDb(null, sql, params);
    }

    /**
     * Consulta outro banco no mesmo servidor e com as mesmas credenciais.
     *
     * @param dbName banco de destino (service name no Oracle); nulo ou em branco usa a configuração
     * @param sql SQL com placeholders {@code ?} para valores
     * @param params valores na ordem dos placeholders
     * @return linhas indexadas pelo nome ou alias das colunas, ou lista vazia
     * @throws IllegalArgumentException se o SQL estiver vazio, o array de parâmetros for nulo
     *                                  ou a configuração for inválida
     * @throws IllegalStateException se faltar configuração obrigatória ou o arquivo não puder ser lido
     * @throws SQLException erro original do driver ao conectar, consultar ou fechar a conexão
     */
    public List<Map<String, Object>> selectInDb(String dbName, String sql, Object... params) throws SQLException {
        validateArguments(sql, params);
        DatabaseConfiguration configuration = DatabaseConfigurationLoader.load().forConnection(connectionName);
        JdbcConnectionSettings settings = JdbcConnectionSettings.from(configuration, dbName);
        try (Connection connection = openConnection(settings)) {
            return createQueryRunner(settings).query(connection, sql, new MapListHandler(), params);
        }
    }

    /**
     * Executa INSERT, UPDATE ou DELETE no banco configurado para esta conexão.
     *
     * @param sql SQL com placeholders {@code ?} para valores
     * @param params valores na ordem dos placeholders
     * @return quantidade de linhas afetadas, não o ID gerado
     * @throws IllegalArgumentException se o SQL estiver vazio, o array de parâmetros for nulo
     *                                  ou a configuração for inválida
     * @throws IllegalStateException se faltar configuração obrigatória ou o arquivo não puder ser lido
     * @throws SQLException erro original do driver ao conectar, alterar ou fechar a conexão
     */
    public int executeUpdate(String sql, Object... params) throws SQLException {
        return executeUpdateInDb(null, sql, params);
    }

    /**
     * Executa INSERT, UPDATE ou DELETE em outro banco do mesmo servidor.
     *
     * @param dbName banco de destino (service name no Oracle); nulo ou em branco usa a configuração
     * @param sql SQL com placeholders {@code ?} para valores
     * @param params valores na ordem dos placeholders
     * @return quantidade de linhas afetadas, não o ID gerado
     * @throws IllegalArgumentException se o SQL estiver vazio, o array de parâmetros for nulo
     *                                  ou a configuração for inválida
     * @throws IllegalStateException se faltar configuração obrigatória ou o arquivo não puder ser lido
     * @throws SQLException erro original do driver ao conectar, alterar ou fechar a conexão
     */
    public int executeUpdateInDb(String dbName, String sql, Object... params) throws SQLException {
        validateArguments(sql, params);
        DatabaseConfiguration configuration = DatabaseConfigurationLoader.load().forConnection(connectionName);
        JdbcConnectionSettings settings = JdbcConnectionSettings.from(configuration, dbName);
        try (Connection connection = openConnection(settings)) {
            return createQueryRunner(settings).update(connection, sql, params);
        }
    }

    private static Connection openConnection(JdbcConnectionSettings settings) throws SQLException {
        return DriverManager.getConnection(settings.jdbcUrl(), settings.connectionProperties());
    }

    private static QueryRunner createQueryRunner(JdbcConnectionSettings settings) {
        StatementConfiguration statement = new StatementConfiguration.Builder()
                .queryTimeout(Duration.ofSeconds(settings.queryTimeoutSeconds())).build();
        return new QueryRunner(statement) {
            @Override
            protected void rethrow(SQLException cause, String sql, Object... params) throws SQLException {
                // DbUtils adicionaria SQL e parâmetros à mensagem; preserve somente o erro original do driver.
                throw cause;
            }
        };
    }

    private static void validateArguments(String sql, Object[] params) {
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalArgumentException("O SQL não pode ser nulo ou estar em branco.");
        }
        if (params == null) {
            throw new IllegalArgumentException(
                    "O array de parâmetros não pode ser nulo. Use (Object) null para um valor SQL NULL.");
        }
    }
}
