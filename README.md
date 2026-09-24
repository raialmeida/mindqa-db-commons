<p align="center">
  <img src="docs/images/mindqa-db-commons-banner.png"
       alt="MindQA DB Commons"
       width="100%">
</p>

[![MvnRepository](https://badges.mvnrepository.com/badge/io.github.raialmeida/mindqa-db-commons/badge.svg?label=MvnRepository&color=green)](https://mvnrepository.com/artifact/io.github.raialmeida/mindqa-db-commons)
[![Java CI](https://github.com/raialmeida/mindqa-db-commons/actions/workflows/ci.yml/badge.svg)](https://github.com/raialmeida/mindqa-db-commons/actions/workflows/ci.yml)

Biblioteca Java para executar CRUD em **SQL Server, PostgreSQL, Oracle e MySQL**
com configuração por variáveis de ambiente ou arquivos `.properties`.
Use uma conexão padrão ou selecione conexões e bases diferentes no mesmo teste.
Por padrão, cada operação abre e fecha sua própria conexão JDBC. Pool de conexões
e cache de configuração podem ser habilitados separadamente.

Documentação completa: [Wiki do GitHub](https://github.com/raialmeida/mindqa-db-commons/wiki).

Funciona em automações Java 17+ de API, interface e integração. Pode ser usada com
RestAssured, Selenium, Cucumber, JUnit ou TestNG. O código da biblioteca não depende
desses frameworks; JUnit e H2 são usados somente nos testes do próprio projeto.

- [Instalação](#instalação)
- [Início rápido](#início-rápido)
- [API em uso](#api-em-uso)
- [Documentation](#documentation)
- [Desenvolvimento e testes](#desenvolvimento-e-testes)
- [Como contribuir](#como-contribuir)

## Instalação

Para consumir a biblioteca, use Java 17 ou superior. Adicione a dependência
ao projeto de automação:

```xml
<dependency>
    <groupId>io.github.raialmeida</groupId>
    <artifactId>mindqa-db-commons</artifactId>
    <version>4.0.0</version>
    <scope>test</scope>
</dependency>
```

Os drivers JDBC de SQL Server, PostgreSQL, Oracle e MySQL são incluídos como
dependências transitivas. RestAssured e o framework de testes devem ser
declarados pelo projeto consumidor. Use a dependência sem `<scope>test</scope>`
se precisar chamá-la em código de produção. Para Gradle e outros gerenciadores,
consulte a página de [instalação e início rápido](https://github.com/raialmeida/mindqa-db-commons/wiki/Instala%C3%A7%C3%A3o-e-in%C3%ADcio-r%C3%A1pido).

Enquanto a versão `4.0.0` não estiver disponível no Maven Central, execute
`mvn clean install` neste repositório para usá-la no repositório Maven local.

## Início rápido

No projeto de automação, crie `src/test/resources/database.properties`:

```properties
DB_DEFAULT_CONNECTION=postgresql
POSTGRESQL_HOST=localhost
POSTGRESQL_PORT=5432
POSTGRESQL_USER=qa_user
POSTGRESQL_PASS=sua_senha
POSTGRESQL_NAME=qa_database
```

Esse arquivo é carregado automaticamente quando não há um seletor explícito.
Também é possível configurar SQL Server, Oracle e MySQL no mesmo arquivo usando
`SQLSERVER_*`, `ORACLE_*` e `MYSQL_*`. Nesse caso, mantenha somente um
`DB_DEFAULT_CONNECTION` para as chamadas que usam a conexão padrão. Com apenas
uma conexão configurada, o seletor pode ser omitido. As variáveis de ambiente
têm prioridade sobre as chaves do arquivo.

Para selecionar outro arquivo ou perfil na execução:

```bash
mvn test -Ddb.config=ambientes/qa.properties
mvn test -Ddb.env=hml
```

A configuração detalhada, inclusive conexões nomeadas, timeouts e opções dos
drivers, está na [referência de configuração](https://github.com/raialmeida/mindqa-db-commons/wiki/Refer%C3%AAncia-de-configura%C3%A7%C3%A3o).

## API em uso

Importe `io.mindqa.database.DatabaseService`. `select` devolve uma lista de
linhas; `execute` executa `INSERT`, `UPDATE` ou `DELETE` e devolve a quantidade
de linhas afetadas. Os parâmetros substituem os placeholders `?` na ordem.

```java
import io.mindqa.database.DatabaseService;

import java.util.List;
import java.util.Map;

String id = "cliente-1";
List<Map<String, Object>> clientes = DatabaseService.select(
        "SELECT nome, email FROM clientes WHERE id = ?", id);

int removidos = DatabaseService.execute(
        "DELETE FROM clientes WHERE id = ?", id);
```

Para outra base na conexão padrão, use `database(nome)` antes da operação. Para
outra conexão, selecione o nome configurado com `connection(nome)`:

```java
DatabaseService.database("qa_auditoria")
        .select("SELECT nome FROM clientes WHERE id = ?", id);

DatabaseService.connection("financeiro")
        .database("qa_clientes")
        .execute("DELETE FROM clientes WHERE id = ?", id);
```

O cliente retornado por `connection(nome)` ou `database(nome)` pode ser reutilizado.
Cada operação gerencia sua própria conexão JDBC. O pool é opcional e pode
reutilizar conexões físicas entre operações. Consulte [Métodos da biblioteca](https://github.com/raialmeida/mindqa-db-commons/wiki/M%C3%A9todos-da-biblioteca)
para os contratos de retorno, erros e demais métodos.

## Documentation

A [Wiki do projeto](https://github.com/raialmeida/mindqa-db-commons/wiki) reúne a
documentação de uso:

- [Instalação e início rápido](https://github.com/raialmeida/mindqa-db-commons/wiki/Instala%C3%A7%C3%A3o-e-in%C3%ADcio-r%C3%A1pido)
- [Métodos da biblioteca](https://github.com/raialmeida/mindqa-db-commons/wiki/M%C3%A9todos-da-biblioteca)
- [Configuração por ambiente](https://github.com/raialmeida/mindqa-db-commons/wiki/Configura%C3%A7%C3%A3o-por-ambiente)
- [Referência de configuração](https://github.com/raialmeida/mindqa-db-commons/wiki/Refer%C3%AAncia-de-configura%C3%A7%C3%A3o)
- [Exemplos de CRUD](https://github.com/raialmeida/mindqa-db-commons/wiki/Exemplos-de-CRUD)
- [Múltiplas conexões e bases](https://github.com/raialmeida/mindqa-db-commons/wiki/M%C3%BAltiplas-conex%C3%B5es-e-bases)
- [Validação no banco com RestAssured e outros testes Java](https://github.com/raialmeida/mindqa-db-commons/wiki/Valida%C3%A7%C3%A3o-no-banco)
- [Erros, pool e cache](https://github.com/raialmeida/mindqa-db-commons/wiki/Erros-e-solu%C3%A7%C3%A3o-de-problemas)

As decisões internas de organização estão em [Arquitetura](docs/architecture.md).

## Desenvolvimento e testes

Para construir este checkout, use JDK 17 ou superior e Maven 3.6.3 ou superior:
os testes dependem de JUnit 6, que requer Java 17. A biblioteca também é
compilada com `--release 17`.

```bash
mvn clean verify
```

Esse comando executa os testes locais, empacota a biblioteca e gera os JARs de
fontes e Javadoc. Os testes comuns usam H2 e não exigem um servidor externo.
Para executar integração com bancos reais de teste:

```bash
mvn clean verify -Pdatabase-integration -Ddb.config=config/qa.properties
```

A [CI](.github/workflows/ci.yml) inclui cenários com os quatro bancos suportados.
Consulte o [guia de contribuição](CONTRIBUTING.md)
para preparar o ambiente e executar os cenários de integração.

## Como contribuir

Contribuições são bem-vindas. Abra uma issue para discutir bugs ou melhorias e
envie um pull request com testes para alterações de comportamento. Siga as
instruções do [CONTRIBUTING.md](CONTRIBUTING.md) antes de enviar a mudança.
Questões de segurança devem seguir a [política de segurança](SECURITY.md).

## Licença

Distribuído sob a [licença MIT](LICENSE).
