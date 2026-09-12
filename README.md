# QA Database Utils

Biblioteca Java para executar CRUD em SQL Server e PostgreSQL nos testes automatizados.
Configure as variáveis de ambiente ou um arquivo `.properties`, adicione a dependência
e chame os métodos estáticos de `DatabaseService`. A biblioteca abre e fecha a conexão
em cada operação.

A API possui quatro métodos e funciona sem Spring, RestAssured ou framework de
injeção de dependências. O tipo do banco é definido pela configuração.

Para manutenção do projeto, consulte a [arquitetura e organização](docs/architecture.md)
e o [guia de contribuição](CONTRIBUTING.md).

## Instalação

Requisitos: JDK 11 ou superior e Maven 3.6.3 ou superior.

No diretório desta biblioteca, execute:

```bash
mvn clean install
```

Isso executa os testes e instala no repositório Maven local o JAR da biblioteca,
o JAR de fontes e o JAR de Javadoc, gerados em `target/`. Nos próximos projetos
de automação, adicione ao `pom.xml`:

```xml
<dependency>
    <groupId>br.com.mindqa</groupId>
    <artifactId>qa-database-utils</artifactId>
    <version>1.0.0</version>
    <scope>test</scope>
</dependency>
```

Os drivers JDBC e o Apache DbUtils são resolvidos transitivamente. RestAssured e o
framework de testes continuam sendo dependências do projeto de automação.
Remova `<scope>test</scope>` se precisar usar a biblioteca em código de produção.

Para usar em outra máquina ou no CI, publique o artefato em um repositório Maven da
equipe (por exemplo, Nexus, Artifactory ou GitHub Packages) e configure esse repositório
no projeto consumidor. Este projeto ainda não publicou o artefato no Maven Central.

## Configuração com qualquer arquivo `.properties`

O arquivo pertence ao **projeto de automação que consome a biblioteca**. Pode ter
qualquer nome, estar em subpastas de `src/test/resources` ou ser um arquivo externo.
O nome é livre; as chaves de conexão devem seguir a tabela de configuração abaixo.
Outras propriedades do mesmo arquivo são ignoradas.

Por exemplo, crie `src/test/resources/ambientes/minha-api-qa.properties`:

```properties
DB_TYPE=postgres
DB_HOST=localhost
DB_PORT=5432
DB_USER=qa_user
DB_PASS=senha-do-ambiente-de-testes
DB_NAME=qa_database
```

Selecione o arquivo ao executar os testes:

```bash
mvn test -Ddb.config=ambientes/minha-api-qa.properties
```

O caminho no classpath começa **depois de `src/test/resources/`**. A biblioteca lê
o recurso também quando ele está empacotado em um JAR. Na IDE, adicione
`-Ddb.config=ambientes/minha-api-qa.properties` às opções da JVM dos testes.
Também é possível definir a variável de ambiente `DB_CONFIG` com esse caminho.

Os arquivos são lidos como UTF-8, com ou sem BOM, usando a sintaxe Java Properties. Também são aceitas
as chaves `db.type`, `db.host`, `db.port`, `db.user`, `db.pass` e `db.name`. Quando
as duas formas estiverem presentes no mesmo arquivo, a forma `DB_*` tem prioridade.
Senhas seguem as regras de escape do formato Properties; os valores lidos não são
aparados nem interpolados. Por exemplo, `${DB_PASS}` seria um valor literal.

### Seleção por ambiente

Você pode escolher qualquer arquivo por ambiente:

```bash
mvn test -Ddb.config=ambientes/minha-api-qa.properties
mvn test -Ddb.config=ambientes/application-hml.properties
```

Se preferir a convenção `database-<ambiente>.properties` na raiz de
`src/test/resources`, use o atalho:

```bash
# Carrega src/test/resources/database-qa.properties
mvn test -Ddb.env=qa

# Carrega src/test/resources/database-hml.properties
mvn test -Ddb.env=hml
```

O ambiente também pode ser definido pela variável `DB_ENV`. A seleção do arquivo
obedece à seguinte ordem: `-Ddb.config`, `DB_CONFIG`, `-Ddb.env`, `DB_ENV`.
Seletores em branco são ignorados. Sem nenhum seletor, a biblioteca usa somente
as variáveis de conexão; ela não procura arquivos `.properties` automaticamente.

### Arquivo externo ou recurso explícito

```bash
# Restringe a busca ao classpath
mvn test -Ddb.config=classpath:ambientes/minha-api-qa.properties

# Lê um caminho relativo ao diretório de execução
mvn test -Ddb.config=file:./config/qa.properties

# Lê um caminho absoluto
mvn test -Ddb.config=/opt/automacao/config/hml.properties
```

Sem prefixo, a busca ocorre primeiro no classpath e depois no sistema de arquivos.
`file:` recebe um caminho de arquivo; não faz download de URLs. Se o arquivo
selecionado não existir, não puder ser lido ou tiver um escape inválido, a chamada
falha com `IllegalStateException`, mesmo que as variáveis de conexão estejam definidas.
Os recursos de leitura são fechados após o carregamento.

### Prioridade dos valores

1. Nome do banco passado ao método `*InDb`, quando informado.
2. Variável de ambiente `DB_*`, quando definida, inclusive com valor vazio.
3. Valor do arquivo `.properties` selecionado.
4. Padrão da biblioteca para tipo e porta.

Você pode, por exemplo, remover `DB_PASS` do arquivo e fornecer a senha pelas
variáveis da IDE ou pelos secrets do pipeline. Uma variável definida em branco
prevalece sobre o arquivo: tipo e porta usam os padrões, senha vazia é preservada
e campos obrigatórios em branco geram erro.

O código dos testes permanece igual:

```java
DatabaseService.select("SELECT * FROM clientes WHERE id = ?", "cliente-1");
```

## Variáveis e propriedades de conexão

Os mesmos valores podem ser fornecidos pelo ambiente ou pelo arquivo selecionado:

| Variável | Obrigatória | Descrição / padrão |
| --- | --- | --- |
| `DB_TYPE` | Não | `sqlserver` ou `postgres`; aceita também `postgresql`. Padrão: `sqlserver`. |
| `DB_HOST` | Sim | Host, IPv4 ou IPv6, sem URL JDBC, porta ou parâmetros adicionais. |
| `DB_PORT` | Não | Porta entre 1 e 65535. Padrão: `1433` para SQL Server e `5432` para PostgreSQL. |
| `DB_USER` | Sim | Usuário de conexão. |
| `DB_PASS` | Sim | Senha, preservada exatamente como informada. Pode ser vazia se o servidor permitir. |
| `DB_NAME` | Condicional | Banco padrão. Dispensável quando um nome é passado aos métodos `*InDb`. |
| `DB_QUERY_TIMEOUT_SECONDS` | Não | Limite de execução de cada statement, em segundos. Padrão: `0` (sem limite definido pela biblioteca). |
| `DB_LOGIN_TIMEOUT_SECONDS` | Não | Limite de conexão entre `1` e `65535` segundos. Padrão: `0` (preserva o comportamento padrão do driver). |

`DB_TYPE` e `DB_PORT` ausentes ou em branco usam os padrões. `DB_TYPE` aceita maiúsculas
e minúsculas. `DB_HOST`, `DB_USER` e o banco escolhido não podem estar em branco.
Um `dbName` nulo ou em branco em `*InDb` utiliza `DB_NAME`.

IPv6 pode ser informado como `::1` ou `[::1]`. Nomes de banco com espaços e
caracteres especiais recebem o escape exigido pelo driver, para preservar o nome
como um único valor da URL. Credenciais são passadas separadamente da URL JDBC.

Os timeouts também aceitam `db.query.timeout.seconds` e `db.login.timeout.seconds`
no arquivo. São opcionais e não alteram o timeout global do `DriverManager`.
Por exemplo:

```properties
DB_QUERY_TIMEOUT_SECONDS=30
DB_LOGIN_TIMEOUT_SECONDS=10
```

O cancelamento da consulta e o estabelecimento da conexão seguem o comportamento
do driver JDBC. O timeout de consulta não limita o tempo total de toda a chamada,
que também inclui carregar a configuração, conectar e processar resultados.

O tipo do banco é definido exclusivamente pela configuração `DB_TYPE` (ou `db.type`
no arquivo). Os métodos recebem o SQL e seus parâmetros; os métodos `*InDb` também
recebem o nome do banco. Sem `DB_PORT`, a porta padrão segue o tipo configurado.

Os métodos `*InDb` alteram apenas o banco de destino, usando o mesmo tipo, host,
porta e credenciais da configuração.

Defina as variáveis no terminal que inicia o Maven, na configuração de execução da
IDE ou nas variáveis do pipeline. Um arquivo `.env` não é carregado automaticamente.

### SQL Server

Exemplo em Bash; substitua os valores pelos do ambiente de testes:

```bash
export DB_TYPE=sqlserver
export DB_HOST=localhost
export DB_PORT=1433
export DB_USER=qa_user
export DB_PASS='substitua-pela-senha-do-ambiente'
export DB_NAME=qa_database
mvn test
```

URL gerada: `jdbc:sqlserver://localhost:1433;databaseName=qa_database;encrypt=false;`.
O parâmetro `encrypt=false` é fixo nesta versão, conforme a configuração solicitada.

### PostgreSQL

```bash
export DB_TYPE=postgres
export DB_HOST=localhost
export DB_PORT=5432
export DB_USER=qa_user
export DB_PASS='substitua-pela-senha-do-ambiente'
export DB_NAME=qa_database
mvn test
```

URL gerada: `jdbc:postgresql://localhost:5432/qa_database`.

## API pública

```java
import br.com.mindqa.database.DatabaseService;
```

| Método | Retorno |
| --- | --- |
| `select(String sql, Object... params)` | `List<Map<String, Object>>` |
| `selectInDb(String dbName, String sql, Object... params)` | `List<Map<String, Object>>` |
| `executeUpdate(String sql, Object... params)` | `int` |
| `executeUpdateInDb(String dbName, String sql, Object... params)` | `int` |

`select` retorna uma lista vazia quando não há resultados. Cada mapa representa uma
linha e usa os nomes ou aliases das colunas como chaves; os valores mantêm os tipos
retornados pelo JDBC. `executeUpdate` serve para `INSERT`, `UPDATE` e `DELETE` e
retorna a quantidade de linhas afetadas, não o ID gerado.

Use `?` para valores e passe os parâmetros na mesma ordem. Para um único SQL `NULL`,
use `(Object) null`. Placeholders não substituem nomes de tabelas, colunas ou bancos.
O SQL deve seguir o dialeto do banco selecionado.
SQL nulo ou em branco e arrays de parâmetros nulos são rejeitados antes de abrir
a conexão. Para uma chamada sem parâmetros, omita o argumento varargs.

### CRUD

O exemplo considera uma tabela `clientes` com `id VARCHAR(36)` como chave primária,
`nome VARCHAR(100)` e `email VARCHAR(150)`.

```java
String id = java.util.UUID.randomUUID().toString();

try {
    int inseridos = DatabaseService.executeUpdate(
            "INSERT INTO clientes (id, nome, email) VALUES (?, ?, ?)",
            id, "Cliente QA", "qa@example.com");

    java.util.List<java.util.Map<String, Object>> clientes = DatabaseService.select(
            "SELECT id, nome, email FROM clientes WHERE id = ?", id);

    int atualizados = DatabaseService.executeUpdate(
            "UPDATE clientes SET nome = ? WHERE id = ?", "Cliente atualizado", id);
} finally {
    int removidos = DatabaseService.executeUpdate("DELETE FROM clientes WHERE id = ?", id);
}
```

### Outro banco no mesmo servidor

```java
// Usa DB_TYPE e consulta outro banco no mesmo servidor.
DatabaseService.selectInDb("qa_auditoria", "SELECT * FROM eventos WHERE id = ?", 10);
DatabaseService.executeUpdateInDb("qa_auditoria", "DELETE FROM eventos WHERE id = ?", 10);
```

## Exemplos com RestAssured e JUnit 5

Os exemplos abaixo pertencem ao projeto de automação que consome a biblioteca e
pressupõem RestAssured e JUnit 5 já configurados. Adapte tabela, campos, URL e rota
para sua aplicação. A API de exemplo lê a mesma tabela `clientes` descrita acima
e devolve `id`, `nome` e `email` em `GET /clientes/{id}`.

### SQL Server: preparar dados e validar a API

Configure `DB_TYPE=sqlserver`, host, porta, usuário, senha e nome do banco nas
variáveis de ambiente ou no arquivo `.properties` selecionado antes de executar este teste:

```java
import br.com.mindqa.database.DatabaseService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ClienteSqlServerTest {
    @Test
    void deveConsultarCliente() {
        String id = UUID.randomUUID().toString();
        try {
            assertEquals(1, DatabaseService.executeUpdate(
                    "INSERT INTO clientes (id, nome, email) VALUES (?, ?, ?)",
                    id, "Cliente SQL Server", "sqlserver@example.com"));

            given()
                    .baseUri("http://localhost:8080")
                    .pathParam("id", id)
            .when()
                    .get("/clientes/{id}")
            .then()
                    .statusCode(200)
                    .body("id", equalTo(id))
                    .body("nome", equalTo("Cliente SQL Server"));

            assertEquals(1, DatabaseService.select(
                    "SELECT id FROM clientes WHERE id = ?", id).size());
        } finally {
            DatabaseService.executeUpdate("DELETE FROM clientes WHERE id = ?", id);
        }
    }
}
```

### PostgreSQL: atualizar dados e validar a API

Configure `DB_TYPE=postgres`, host, porta, usuário, senha e nome do banco nas
variáveis de ambiente ou no arquivo `.properties` selecionado antes de executar este teste:

```java
import br.com.mindqa.database.DatabaseService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientePostgresTest {
    @Test
    void deveConsultarClienteAtualizado() {
        String id = UUID.randomUUID().toString();
        try {
            assertEquals(1, DatabaseService.executeUpdate(
                    "INSERT INTO clientes (id, nome, email) VALUES (?, ?, ?)",
                    id, "Cliente inicial", "postgres@example.com"));

            assertEquals(1, DatabaseService.executeUpdate(
                    "UPDATE clientes SET nome = ? WHERE id = ?", "Cliente atualizado", id));

            given()
                    .baseUri("http://localhost:8080")
                    .pathParam("id", id)
            .when()
                    .get("/clientes/{id}")
            .then()
                    .statusCode(200)
                    .body("nome", equalTo("Cliente atualizado"));

            List<Map<String, Object>> clientes = DatabaseService.select(
                    "SELECT nome FROM clientes WHERE id = ?", id);
            assertEquals(1, clientes.size());
            assertEquals("Cliente atualizado", clientes.get(0).get("nome"));
        } finally {
            DatabaseService.executeUpdate("DELETE FROM clientes WHERE id = ?", id);
        }
    }
}
```

## Conexões, erros e validação

Cada chamada lê e valida sua configuração uma vez, preservando os mesmos valores
durante a execução e no diagnóstico de erros. As configurações são imutáveis por
chamada e os métodos podem ser usados concorrentemente, com conexões independentes.

Cada chamada usa uma conexão independente em auto-commit e a fecha com
`try-with-resources`, inclusive quando o SQL falha. Não há pool nem transação
compartilhada entre chamadas; alterações confirmadas ficam visíveis à API. O
Apache DbUtils gerencia os statements e os result sets.

Falhas JDBC geram `DatabaseException`, uma subclasse de `RuntimeException`, com a
operação, o banco, o SQLState e o código do driver. A `SQLException` original é
preservada como causa (`getCause()`), inclusive erros suprimidos no fechamento da
conexão. A biblioteca não acrescenta o SQL nem os parâmetros à mensagem da exceção.
A causa mantém o diagnóstico do driver, que pode conter dados do SQL.

```java
import br.com.mindqa.database.DatabaseException;

try {
    DatabaseService.select("SELECT id FROM clientes WHERE id = ?", "cliente-1");
} catch (DatabaseException exception) {
    String operacao = exception.getOperation();
    String banco = exception.getDatabase();
    String sqlState = exception.getSqlState();
    int codigoDriver = exception.getErrorCode();
    throw exception;
}
```

Configuração ausente ou falha
na leitura do arquivo selecionado gera `IllegalStateException`; tipo ou porta
inválidos, host com parâmetros JDBC, caracteres de controle no nome do banco e
timeouts inválidos geram `IllegalArgumentException`.

## Desenvolvimento e testes

```bash
mvn clean verify
```

O build valida JDK 11+ e Maven 3.6.3+, compila com `--release 11` e falha em avisos
de compilação ou de Javadoc. As versões dos plugins estão fixadas, e os arquivos
JAR usam um timestamp controlado por `project.build.outputTimestamp`.

Artefatos gerados:

- `target/qa-database-utils-1.1.0.jar`: biblioteca com licença MIT no `META-INF`.
- `target/qa-database-utils-1.1.0-sources.jar`: fontes para navegação na IDE.
- `target/qa-database-utils-1.1.0-javadoc.jar`: documentação da API.

### Testes sem servidor de banco

Os testes de configuração e parsing usam os próprios drivers JDBC sem abrir
conexões de rede. Os testes de execução isolam as variáveis de ambiente em
processos Java e usam um driver de teste
com H2 em memória para verificar URLs, credenciais, CRUD parametrizado, seleção de
banco e fechamento de conexões. Também verificam arquivos com nomes livres no
classpath, recursos em JAR, arquivos externos, seleção por ambiente, UTF-8,
precedência dos valores, argumentos inválidos, concorrência, timeouts e preservação
das exceções e dos recursos JDBC. H2 e JUnit têm escopo `test` e não são dependências
transitivas dos consumidores.

### Integração com SQL Server e PostgreSQL reais

O perfil `database-integration` executa `DatabaseServiceIT` pelo Maven Failsafe.
Esse teste fica no pacote `br.com.mindqa.database.integration` e usa somente a API
pública da biblioteca.
Configure uma instância de testes acessível, com permissão para criar e remover
tabelas, e execute:

```bash
mvn clean verify -Pdatabase-integration -Ddb.config=config/qa.properties
```

Também é possível usar somente as variáveis `DB_*`. Execute uma vez com
`DB_TYPE=postgres` e outra com `DB_TYPE=sqlserver`. O teste usa uma tabela com nome
único, verifica CRUD e os métodos `*InDb`, e remove a tabela ao terminar.

O workflow [Java CI](.github/workflows/ci.yml) define validação com JDK 11, 17 e 21,
além de integração com PostgreSQL 16 e SQL Server 2022 em containers descartáveis.
As credenciais do workflow pertencem somente a esses containers de testes. Criar
ou editar o workflow localmente não executa os jobs no GitHub.

## Organização do código

```text
src/
├── main/java/br/com/mindqa/database/
│   ├── DatabaseService.java
│   ├── DatabaseException.java
│   ├── DatabaseConfiguration.java
│   ├── DatabaseConfigurationLoader.java
│   ├── JdbcConnectionSettings.java
│   └── package-info.java
└── test/java/br/com/mindqa/database/
    ├── DatabaseServiceTest.java
    ├── DatabaseConfigurationLoaderTest.java
    ├── JdbcConnectionSettingsTest.java
    ├── support/
    │   ├── JdbcScenarioProcess.java
    │   └── JdbcScenarioRunner.java
    └── integration/
        └── DatabaseServiceIT.java
```

| Classe | Responsabilidade | Visibilidade |
| --- | --- | --- |
| `DatabaseService` | Quatro operações de CRUD e ciclo de vida das conexões. | Pública |
| `DatabaseException` | Diagnóstico das falhas JDBC. | Pública |
| `DatabaseConfigurationLoader` | Seleção e leitura das fontes de configuração. | Restrita ao pacote |
| `DatabaseConfiguration` | Valores imutáveis e precedência do ambiente sobre o arquivo. | Restrita ao pacote |
| `JdbcConnectionSettings` | Valores JDBC validados, URL, credenciais e timeouts em segundos. | Restrita ao pacote |

O pacote principal agrupa a funcionalidade de banco de dados e mantém os detalhes
internos encapsulados. Os testes usam o layout Maven padrão; `*Test` roda com
Surefire e `*IT` com Failsafe no perfil de integração. Os auxiliares de teste
ficam em `support` e não entram no JAR distribuído.

As decisões de encapsulamento, nomenclatura e evolução estão descritas em
[docs/architecture.md](docs/architecture.md).

Referências: [QueryRunner / Apache DbUtils](https://commons.apache.org/proper/commons-dbutils/apidocs/org/apache/commons/dbutils/QueryRunner.html),
[conexão PostgreSQL JDBC](https://jdbc.postgresql.org/documentation/use/),
[conexão SQL Server JDBC](https://learn.microsoft.com/en-us/sql/connect/jdbc/building-the-connection-url)
e [uso do RestAssured](https://github.com/rest-assured/rest-assured/wiki/Usage).
