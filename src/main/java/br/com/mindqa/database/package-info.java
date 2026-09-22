/**
 * Utilitários para automações com SQL Server, PostgreSQL, Oracle e MySQL.
 * A entrada pública para CRUD é {@link br.com.mindqa.database.DatabaseService}.
 * Conexões nomeadas são acessadas por {@link br.com.mindqa.database.DatabaseClient}.
 * Falhas JDBC são expostas por {@link br.com.mindqa.database.DatabaseException},
 * que preserva a exceção original do driver como causa.
 * A leitura de configuração e os valores JDBC são detalhes internos com acesso restrito ao pacote.
 */
package br.com.mindqa.database;
