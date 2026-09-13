package br.com.mindqa.database;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Fachada de CRUD para SQL Server, PostgreSQL, Oracle e MySQL.
 * <p>Os métodos estáticos usam a conexão padrão. Para selecionar uma conexão nomeada,
 * use {@link #connection(String)}. O tipo e as credenciais vêm do ambiente ou do arquivo
 * Properties. Cada operação abre e fecha sua própria conexão em auto-commit.</p>
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
     * @return cliente imutável e reutilizável; sua configuração é lida em cada operação
     * @throws IllegalArgumentException se o nome for nulo, vazio ou inválido
     */
    public static DatabaseClient connection(String name) {
        return new DatabaseClient(DatabaseConfiguration.normalizeConnectionName(name));
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
     * Consulta outro banco no servidor da conexão padrão, usando as mesmas credenciais.
     *
     * @param dbName banco de destino (service name no Oracle); nulo ou em branco usa a configuração
     * @param sql SQL com placeholders {@code ?} para valores
     * @param params valores na ordem dos placeholders
     * @return linhas indexadas pelo nome ou alias das colunas, ou lista vazia
     * @throws IllegalArgumentException se os argumentos ou a configuração forem inválidos
     * @throws IllegalStateException se faltar configuração, a seleção for ambígua ou o arquivo não puder ser lido
     * @throws SQLException erro original do driver ao conectar, consultar ou fechar a conexão
     */
    public static List<Map<String, Object>> selectInDb(String dbName, String sql, Object... params) throws SQLException {
        return DEFAULT_CLIENT.selectInDb(dbName, sql, params);
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
    public static int executeUpdate(String sql, Object... params) throws SQLException {
        return DEFAULT_CLIENT.executeUpdate(sql, params);
    }

    /**
     * Executa INSERT, UPDATE ou DELETE em outro banco do servidor da conexão padrão.
     *
     * @param dbName banco de destino (service name no Oracle); nulo ou em branco usa a configuração
     * @param sql SQL com placeholders {@code ?} para valores
     * @param params valores na ordem dos placeholders
     * @return quantidade de linhas afetadas, não o ID gerado
     * @throws IllegalArgumentException se os argumentos ou a configuração forem inválidos
     * @throws IllegalStateException se faltar configuração, a seleção for ambígua ou o arquivo não puder ser lido
     * @throws SQLException erro original do driver ao conectar, alterar ou fechar a conexão
     */
    public static int executeUpdateInDb(String dbName, String sql, Object... params) throws SQLException {
        return DEFAULT_CLIENT.executeUpdateInDb(dbName, sql, params);
    }
}
