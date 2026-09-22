package io.mindqa.database;

import java.util.List;
import java.util.Map;

/**
 * CRUD para SQL Server, PostgreSQL, Oracle e MySQL.
 * <p>
 * Os métodos estáticos usam a conexão padrão. Para selecionar uma conexão
 * nomeada,
 * use {@link #connection(String)}. O tipo e as credenciais vêm do ambiente ou
 * do arquivo
 * {@code .properties}. Cada operação usa uma conexão exclusiva em auto-commit e
 * a fecha ou
 * devolve ao pool ao terminar.
 * </p>
 */
public final class DatabaseService {
    private static final DatabaseClient DEFAULT_CLIENT = new DatabaseClient(null);

    private DatabaseService() {
    }

    /**
     * Seleciona uma conexão pelo nome configurado, sem abrir uma conexão JDBC.
     *
     * @param name nome da conexão; letras ASCII e números, começando com uma letra;
     *             ignora maiúsculas e espaços nas extremidades
     * @return novo cliente imutável e reutilizável; a configuração é resolvida
     *         somente ao executar uma operação
     * @throws IllegalArgumentException se o nome for nulo, vazio ou inválido
     */
    public static DatabaseClient connection(String name) {
        return new DatabaseClient(DatabaseConfiguration.normalizeConnectionName(name));
    }

    /**
     * Cria um cliente para outra base da conexão padrão. A chamada não altera a
     * conexão padrão, não abre uma conexão JDBC nem carrega a configuração.
     *
     * @param databaseName nome da base de destino ou service name no Oracle.
     * @return novo cliente imutável com a base selecionada na conexão padrão
     */
    public static DatabaseClient database(String databaseName) {
        return DEFAULT_CLIENT.database(databaseName);
    }

    /**
     * Descarta os snapshots de configuração. A próxima operação carrega a fonte
     * novamente.
     * Operações já iniciadas mantêm seu snapshot; os pools não são fechados por
     * esta chamada.
     */
    public static void clearConfigurationCache() {
        DatabaseConfigurationLoader.clearCache();
    }

    /**
     * Fecha todos os pools desta biblioteca. Use no encerramento da suíte, após
     * concluir as operações.
     * Uma operação posterior pode criar novos pools. Também há fechamento no
     * encerramento da JVM.
     */
    public static void closePools() {
        JdbcConnectionPools.closeShared();
    }

    /**
     * Consulta usando a conexão e a base padrão selecionadas pela configuração.
     *
     * @param sql    SQL com placeholders {@code ?} para valores
     * @param params valores na ordem dos placeholders; use {@code (Object) null}
     *               para SQL NULL
     * @return lista de linhas, em que cada linha é um mapa indexado pelo nome ou
     *         alias
     *         da coluna; retorna uma lista vazia quando nenhum registro for
     *         encontrado
     * @throws IllegalArgumentException se os argumentos ou a configuração forem
     *                                  inválidos
     * @throws IllegalStateException    se faltar configuração, a conexão padrão for
     *                                  ambígua
     *                                  ou o arquivo não puder ser lido
     * @throws DatabaseException        se ocorrer uma falha JDBC ao conectar,
     *                                  consultar ou fechar
     *                                  a conexão; a {@code SQLException} original
     *                                  permanece como causa
     */
    public static List<Map<String, Object>> select(String sql, Object... params) {
        return DEFAULT_CLIENT.select(sql, params);
    }

    /**
     * Executa INSERT, UPDATE ou DELETE usando a conexão e a base padrão
     * selecionadas pela configuração.
     *
     * @param sql    SQL com placeholders {@code ?} para valores
     * @param params valores na ordem dos placeholders
     * @return quantidade de linhas afetadas
     * @throws IllegalArgumentException se os argumentos ou a configuração forem
     *                                  inválidos
     * @throws IllegalStateException    se faltar configuração, a conexão padrão for
     *                                  ambígua
     *                                  ou o arquivo não puder ser lido
     * @throws DatabaseException        se ocorrer uma falha JDBC ao conectar,
     *                                  executar a alteração
     *                                  ou fechar a conexão; a {@code SQLException}
     *                                  original permanece como causa
     */
    public static int execute(String sql, Object... params) {
        return DEFAULT_CLIENT.execute(sql, params);
    }
}
