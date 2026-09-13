# Guia de contribuição

## Preparar o ambiente

Use JDK 11 ou superior e Maven 3.6.3 ou superior. `JAVA_HOME` deve apontar para um
JDK válido. O projeto compila com `--release 11`, inclusive quando o build usa um
JDK mais recente.

```bash
java -version
mvn -version
mvn clean verify
```

O build gera o JAR da biblioteca, as fontes e o Javadoc em `target/`. Os testes
padrão usam arquivos temporários e H2; não exigem um servidor externo.

## Onde fazer cada alteração

| Alteração | Local principal |
| --- | --- |
| Contrato público de CRUD e clientes nomeados | `DatabaseService`, `DatabaseClient` e `DatabaseServiceTest` |
| Diagnóstico de erro JDBC | `DatabaseException` e cenários de falha da API |
| Seleção e leitura de arquivos | `DatabaseConfigurationLoader` e seu teste |
| Seleção de conexão, precedência e captura dos valores | `DatabaseConfiguration` e `DatabaseConfigurationTest` |
| Validação JDBC, URL ou timeout | `JdbcConnectionSettings` e seu teste |
| Execução de cenários em outra JVM | `src/test/java/.../support` |
| Comportamento com bancos reais | `src/test/java/.../integration` |

Consulte [a arquitetura](docs/architecture.md) para entender as fronteiras entre
as classes. Auxiliares de produção permanecem com acesso restrito ao pacote.

## Convenções

O `.editorconfig` define UTF-8, finais de linha LF e indentação de quatro espaços
para Java/XML e dois para YAML. Use nomes em inglês nos identificadores e explique
a API em português no Javadoc e no README. Indique a unidade nos nomes que
representem tempo, tamanho ou outras medidas.

Mantenha as versões das dependências nas propriedades do `pom.xml`. Surefire e
Failsafe usam a mesma propriedade de versão. Novas bibliotecas usadas apenas por
testes precisam de escopo `test`.

Preserve o contrato dos quatro métodos de CRUD, a seleção por `connection(nome)` e
a prioridade das variáveis de ambiente sobre o arquivo. Conexões nomeadas não
herdam credenciais da raiz ou de outros nomes. Ao modificar comportamento, adicione ou ajuste os testes do caso
afetado. Refatorações de nomes e organização devem continuar passando na suíte
existente. Documente qualquer mudança visível ao consumidor.

Use `mvn clean verify` para verificar compilação, testes, empacotamento e Javadoc.
O build trata avisos de compilação e de Javadoc como falhas. Não inclua `target/`,
arquivos de IDE ou credenciais reais nos arquivos versionados.

## Integração com bancos reais

Configure uma instância dedicada a testes com permissão para criar e remover
tabelas. Em seguida, execute:

```bash
mvn clean verify -Pdatabase-integration -Ddb.config=config/qa.properties
```

Também é possível fornecer somente variáveis `DB_*`. O teste cria uma tabela com
nome único e a remove ao terminar. `DatabaseServiceIT` deve exercitar o projeto
como um consumidor, importando somente os tipos públicos.

O [workflow de CI](.github/workflows/ci.yml) verifica JDK 11, 17 e 21 e executa
integração com PostgreSQL 16, SQL Server 2022, MySQL 8.4 e Oracle Free em containers descartáveis.

## Distribuição

```bash
mvn clean install
```

Esse comando instala os três artefatos no Maven local. Publicação em repositório
remoto depende do repositório e das credenciais definidos pela equipe. Antes de
uma publicação, revise a versão do artefato e a compatibilidade da API; atualizar
o código local não publica uma nova versão.
