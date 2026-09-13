package br.com.mindqa.database;

import java.sql.SQLException;

/**
 * Falha JDBC com contexto da operação e a exceção original do driver como causa.
 * A mensagem desta classe não inclui o SQL, os parâmetros ou a senha de conexão.
 * A causa mantém o diagnóstico original do driver e pode conter informações do SQL.
 */
public final class DatabaseException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Operação que originou a falha. */
    private final String operation;
    /** Banco selecionado antes da abertura da conexão. */
    private final String database;
    /** SQLState informado pelo driver. */
    private final String sqlState;
    /** Código de erro informado pelo driver. */
    private final int errorCode;

    DatabaseException(String operation, String database, SQLException cause) {
        super("Falha ao executar " + operation + " no banco '" + database
                + "' [SQLState=" + cause.getSQLState() + ", código=" + cause.getErrorCode() + "].", cause);
        this.operation = operation;
        this.database = database;
        this.sqlState = cause.getSQLState();
        this.errorCode = cause.getErrorCode();
    }

    /**
     * Identifica a operação que falhou.
     * @return operação que estava sendo executada
     */
    public String getOperation() {
        return operation;
    }

    /**
     * Identifica o banco selecionado.
     * @return nome do banco efetivamente selecionado para a operação
     */
    public String getDatabase() {
        return database;
    }

    /**
     * Expõe o SQLState preservado do driver.
     * @return SQLState do driver, ou {@code null} quando não informado
     */
    public String getSqlState() {
        return sqlState;
    }

    /**
     * Expõe o código de erro preservado do driver.
     * @return código de erro específico do driver
     */
    public int getErrorCode() {
        return errorCode;
    }
}
