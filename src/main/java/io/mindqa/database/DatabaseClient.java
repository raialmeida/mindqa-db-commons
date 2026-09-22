package io.mindqa.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.apache.commons.dbutils.QueryRunner;
import org.apache.commons.dbutils.StatementConfiguration;
import org.apache.commons.dbutils.handlers.MapListHandler;

/**
 * Operações JDBC para uma conexão selecionada por
 * {@link DatabaseService#connection(String)}.
 * <p>
 * Cada chamada usa uma configuração imutável e sua própria conexão em
 * auto-commit.
 * Este cliente guarda somente os nomes da conexão e da base selecionadas e pode
 * ser reutilizado. Criá-lo não abre
 * conexões JDBC; por padrão, a configuração é lida a cada operação. Cache e
 * pool são opcionais.
 * Não requer fechamento pelo consumidor; com pool, fechar a conexão a devolve
 * ao pool.
 * Chamadas concorrentes não compartilham conexões ou estado mutável.
 * </p>
 */
public final class DatabaseClient {
    private final String connectionName;
    private final String databaseName;

    DatabaseClient(String connectionName) {
        this(connectionName, null);
    }

    private DatabaseClient(String connectionName, String databaseName) {
        this.connectionName = connectionName;
        this.databaseName = databaseName;
    }

    /**
     * Cria um cliente para outra base, preservando a conexão, as credenciais e as
     * demais opções do cliente atual. A chamada não altera este cliente, não abre
     * uma conexão JDBC nem carrega a configuração.
     *
     * @param databaseName nome da base de destino ou service name no Oracle.
     * @return novo cliente imutável com a base selecionada
     */
    public DatabaseClient database(String databaseName) {
        return new DatabaseClient(connectionName, databaseName);
    }

    /**
     * Consulta usando a conexão e a base selecionada por este cliente.
     *
     * @param sql    SQL com placeholders {@code ?} para valores
     * @param params valores na ordem dos placeholders; use {@code (Object) null}
     *               para SQL NULL
     * @return lista de linhas, em que cada linha é um mapa indexado pelo nome ou
     *         alias
     *         da coluna; retorna uma lista vazia quando nenhum registro for
     *         encontrado
     * @throws IllegalArgumentException se o SQL estiver vazio, o array de
     *                                  parâmetros for nulo
     *                                  ou a configuração for inválida
     * @throws IllegalStateException    se a conexão não estiver configurada, faltar
     *                                  configuração obrigatória
     *                                  ou o arquivo não puder ser lido
     * @throws DatabaseException        se ocorrer uma falha JDBC ao conectar,
     *                                  consultar ou fechar
     *                                  a conexão; a {@link SQLException} original
     *                                  permanece como causa
     */
    public List<Map<String, Object>> select(String sql, Object... params) {
        validateArguments(sql, params);
        DatabaseConfiguration configuration = DatabaseConfigurationLoader.load().forConnection(connectionName);
        JdbcConnectionSettings settings = JdbcConnectionSettings.from(configuration, databaseName);
        return withConnection(settings,
                connection -> createQueryRunner(settings).query(connection, sql, new MapListHandler(), params));
    }

    /**
     * Executa INSERT, UPDATE ou DELETE usando a conexão e a base selecionada por
     * este cliente.
     *
     * @param sql    SQL com placeholders {@code ?} para valores
     * @param params valores na ordem dos placeholders
     * @return quantidade de linhas afetadas, não o ID gerado
     * @throws IllegalArgumentException se o SQL estiver vazio, o array de
     *                                  parâmetros for nulo
     *                                  ou a configuração for inválida
     * @throws IllegalStateException    se a conexão não estiver configurada, faltar
     *                                  configuração obrigatória
     *                                  ou o arquivo não puder ser lido
     * @throws DatabaseException        se ocorrer uma falha JDBC ao conectar,
     *                                  executar a alteração
     *                                  ou fechar a conexão; a {@link SQLException}
     *                                  original permanece como causa
     */
    public int execute(String sql, Object... params) {
        validateArguments(sql, params);
        DatabaseConfiguration configuration = DatabaseConfigurationLoader.load().forConnection(connectionName);
        JdbcConnectionSettings settings = JdbcConnectionSettings.from(configuration, databaseName);
        return withConnection(settings,
                connection -> createQueryRunner(settings).update(connection, sql, params));
    }

    private static <T> T withConnection(JdbcConnectionSettings settings, JdbcOperation<T> operation) {
        try (Connection connection = openConnection(settings)) {
            return operation.execute(connection);
        } catch (SQLException exception) {
            throw new DatabaseException(exception);
        }
    }

    private static Connection openConnection(JdbcConnectionSettings settings) throws SQLException {
        return JdbcConnectionPools.open(settings);
    }

    private static QueryRunner createQueryRunner(JdbcConnectionSettings settings) {
        StatementConfiguration statement = new StatementConfiguration.Builder()
                .queryTimeout(Duration.ofSeconds(settings.queryTimeoutSeconds())).build();
        return new QueryRunner(statement) {
            @Override
            protected void rethrow(SQLException cause, String sql, Object... params) throws SQLException {
                // DbUtils adicionaria SQL e parâmetros à mensagem; preserve somente o erro
                // original do driver.
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

    @FunctionalInterface
    private interface JdbcOperation<T> {
        T execute(Connection connection) throws SQLException;
    }
}
