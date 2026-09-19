package br.com.mindqa.database;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Fachada de CRUD para SQL Server, PostgreSQL, Oracle e MySQL.
 * <p>Os métodos estáticos usam a conexão padrão. Para selecionar uma conexão nomeada,
 * use {@link #connection(String)}. O tipo e as credenciais vêm do ambiente ou do arquivo
 * Properties. Cada operação usa uma conexão exclusiva em auto-commit e a fecha ou
 * devolve ao pool ao terminar.</p>
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
     * @return cliente imutável e reutilizável; sua configuração respeita a opção de cache
     * @throws IllegalArgumentException se o nome for nulo, vazio ou inválido
     */
    public static DatabaseClient connection(String name) {
        return new DatabaseClient(DatabaseConfiguration.normalizeConnectionName(name));
    }

    /**
     * Descarta os snapshots de configuração. A próxima operação carrega a fonte novamente.
     * Operações já iniciadas mantêm seu snapshot; os pools não são fechados por esta chamada.
     */
    public static void clearConfigurationCache() {
        DatabaseConfigurationLoader.clearCache();
    }

    /**
     * Fecha todos os pools desta biblioteca. Use no encerramento da suíte, após concluir as operações.
     * Uma operação posterior pode criar novos pools. Também há fechamento no encerramento da JVM.
     */
    public static void closePools() {
        JdbcConnectionPools.closeShared();
    }

    /**
     * Consulta a conexão padrão.
     *
     * @param sql SQL com placeholders {@code ?} para valores
     * @param params valores na ordem dos placeholders; use {@code (Object) null} para SQL NULL
     * @return linhas indexadas pelo nome ou alias das colunas, ou lista vazia
     * @throws IllegalArgumentException se os argumentos ou a configuração forem inválidos
     * @throws IllegalStateException se faltar configuração, a seleção for ambígua ou o arquivo não puder ser lido
     * @throws SQLException erro original do driver ao conectar, consultar ou fechar a conexão
     */
    public static List<Map<String, Object>> select(String sql, Object... params) throws SQLException {
        return DEFAULT_CLIENT.select(sql, params);
    }

    /**
     * Executa INSERT, UPDATE ou DELETE na conexão padrão.
     *
     * @param sql SQL com placeholders {@code ?} para valores
     * @param params valores na ordem dos placeholders
     * @return quantidade de linhas afetadas, não o ID gerado
     * @throws IllegalArgumentException se os argumentos ou a configuração forem inválidos
     * @throws IllegalStateException se faltar configuração, a seleção for ambígua ou o arquivo não puder ser lido
     * @throws SQLException erro original do driver ao conectar, alterar ou fechar a conexão
     */
    public static int execute(String sql, Object... params) throws SQLException {
        return DEFAULT_CLIENT.execute(sql, params);
    }
}
