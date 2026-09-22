/**
 * Utilitários para automações com SQL Server, PostgreSQL, Oracle e MySQL.
 * A entrada pública para CRUD é {@link io.mindqa.database.DatabaseService}.
 * Conexões nomeadas são acessadas por {@link io.mindqa.database.DatabaseClient}.
 * Falhas JDBC são expostas por {@link io.mindqa.database.DatabaseException},
 * que preserva a exceção original do driver como causa.
 * A leitura de configuração e os valores JDBC são detalhes internos com acesso restrito ao pacote.
 */
package io.mindqa.database;
