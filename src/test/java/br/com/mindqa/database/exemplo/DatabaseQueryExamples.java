package br.com.mindqa.database.exemplo;

import br.com.mindqa.database.DatabaseClient;
import br.com.mindqa.database.DatabaseService;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exemplos chamados pelos testes do projeto consumidor; não são executados
 * automaticamente.
 * <p>
 * O recurso {@code database.properties} é carregado automaticamente quando está
 * na raiz
 * do classpath de testes do projeto consumidor. O arquivo de exemplo
 * declara as conexões postgresql, sqlserver, mysql e oracle, com postgresql
 * como padrão.
 * Adapte tabelas, colunas e bases ao ambiente da aplicação.
 * </p>
 */
public final class DatabaseQueryExamples {
    private DatabaseQueryExamples() {
    }

    public static List<Map<String, Object>> consultarClientesPorId(String clienteId) throws SQLException {
        return DatabaseService.select(
                "SELECT id, nome, email FROM clientes WHERE id = ?", clienteId);
    }

    /**
     * O ID é gerado pelo teste; o retorno informa a quantidade de linhas inseridas.
     */
    public static int cadastrarNovoCliente(String nome, String email) throws SQLException {
        String clienteId = UUID.randomUUID().toString();
        return DatabaseService.executeUpdate(
                "INSERT INTO clientes (id, nome, email) VALUES (?, ?, ?)", clienteId, nome, email);
    }

    public static int atualizarEmailCliente(String clienteId, String novoEmail) throws SQLException {
        return DatabaseService.executeUpdate(
                "UPDATE clientes SET email = ? WHERE id = ?", novoEmail, clienteId);
    }

    public static int deletarCliente(String clienteId) throws SQLException {
        return DatabaseService.executeUpdate("DELETE FROM clientes WHERE id = ?", clienteId);
    }

    public static List<Map<String, Object>> listarTodosOsClientes() throws SQLException {
        return DatabaseService.select("SELECT id, nome, email FROM clientes");
    }

    public static List<Map<String, Object>> filtrarClientesPorNomeEDominio(String nomeComeco, String dominio) throws SQLException {
        return DatabaseService.select("SELECT id, nome, email FROM clientes WHERE nome LIKE ? AND email LIKE ?",
                nomeComeco + "%", "%" + dominio);
    }

    /**
     * Troca a base dentro do servidor da conexão padrão. No Oracle, troca o service
     * name.
     */
    public static List<Map<String, Object>> consultarOutroBancoDaInstancia(String nomeDoBanco, String clienteId) throws SQLException {
        return DatabaseService.selectInDb(nomeDoBanco,
                "SELECT id, nome, email FROM clientes WHERE id = ?", clienteId);
    }

    /** Seleciona tanto a conexão SQL Server quanto uma base desse servidor. */
    public static List<Map<String, Object>> consultarEmBaseEspecificaSqlServer(String nomeBase, String clienteId) throws SQLException {
        return DatabaseService.connection("sqlserver").selectInDb(nomeBase,
                "SELECT id, nome, email FROM clientes WHERE id = ?", clienteId);
    }

    public static List<Map<String, Object>> consultarSqlServer(String clienteId) throws SQLException {
        return DatabaseService.connection("sqlserver").select(
                "SELECT id, nome, email FROM clientes WHERE id = ?", clienteId);
    }

    public static List<Map<String, Object>> consultarPostgreSQL(String clienteId) throws SQLException {
        return DatabaseService.connection("postgresql").select(
                "SELECT id, nome, email FROM clientes WHERE id = ?", clienteId);
    }

    public static List<Map<String, Object>> consultarMySQL(String clienteId) throws SQLException {
        return DatabaseService.connection("mysql").select(
                "SELECT id, nome, email FROM clientes WHERE id = ?", clienteId);
    }

    public static List<Map<String, Object>> consultarOracle(String clienteId) throws SQLException {
        return DatabaseService.connection("oracle").select(
                "SELECT id, nome, email FROM clientes WHERE id = ?", clienteId);
    }

    /**
     * Consulta quatro motores e bases diferentes no mesmo fluxo de teste.
     * As bases e os registros devem existir antes da execução deste exemplo.
     */
    public static void validarCadastroEmBancosEBasesDiferentes(String clienteId, String emailEsperado) throws SQLException {
        String sql = "SELECT email FROM clientes WHERE id = ?";

        List<Map<String, Object>> postgres = DatabaseService.connection("postgresql")
                .selectInDb("qa_clientes", sql, clienteId);
        List<Map<String, Object>> sqlServer = DatabaseService.connection("sqlserver")
                .selectInDb("qa_replica", sql, clienteId);
        List<Map<String, Object>> mysql = DatabaseService.connection("mysql")
                .selectInDb("qa_loja", sql, clienteId);
        List<Map<String, Object>> oracle = DatabaseService.connection("oracle")
                .selectInDb("FREEPDB1", sql, clienteId);

        for (List<Map<String, Object>> resultado : List.of(postgres, sqlServer, mysql, oracle)) {
            assertEquals(1, resultado.size(), "O cadastro deve existir em cada destino");
            assertEquals(emailEsperado, resultado.get(0).get("email"));
        }
    }

    /**
     * Prepara, valida e limpa o dado; cada operação confirma sua própria alteração.
     */
    public static void testarCadastroComValidacao() throws SQLException {
        DatabaseClient banco = DatabaseService.connection("postgresql");
        String clienteId = UUID.randomUUID().toString();
        String nome = "Cliente QA";
        String email = "qa-" + clienteId + "@example.com";

        try {
            assertEquals(1, banco.executeUpdate(
                    "INSERT INTO clientes (id, nome, email) VALUES (?, ?, ?)", clienteId, nome, email));
            List<Map<String, Object>> resultado = banco.select(
                    "SELECT nome, email FROM clientes WHERE id = ?", clienteId);
            assertEquals(1, resultado.size());
            assertEquals(nome, resultado.get(0).get("nome"));
            assertEquals(email, resultado.get(0).get("email"));
        } finally {
            banco.executeUpdate("DELETE FROM clientes WHERE id = ?", clienteId);
        }
    }
}
