package io.mindqa.database;

import java.sql.SQLException;

/**
 * Exceção não verificada que preserva uma falha JDBC original.
 *
 * <p>A mensagem é a mesma fornecida pelo driver e a {@link SQLException} original
 * permanece disponível por {@link #getCause()} e {@link #getSQLException()}.
 * SQLState, código do fornecedor, próximas exceções e exceções suprimidas não são
 * copiados nem alterados, pois permanecem no objeto original.</p>
 */
public final class DatabaseException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Cria uma falha de banco preservando a exceção JDBC recebida.
     *
     * @param cause exceção original lançada pelo driver ou pela infraestrutura JDBC
     */
    public DatabaseException(SQLException cause) {
        super(requireCause(cause).getMessage(), cause);
    }

    /**
     * Retorna a mesma instância da exceção JDBC recebida no construtor.
     *
     * @return exceção JDBC original
     */
    public SQLException getSQLException() {
        return (SQLException) getCause();
    }

    private static SQLException requireCause(SQLException cause) {
        if (cause == null) {
            throw new IllegalArgumentException("A SQLException original não pode ser nula.");
        }
        return cause;
    }
}
