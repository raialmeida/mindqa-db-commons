# Política de Segurança

## Versões suportadas

As correções de segurança são direcionadas à versão mais recente publicada no Maven Central. Antes de enviar um relato, confirme se o problema também ocorre nessa versão.

Recomendamos sempre utilizar a versão mais recente disponível da biblioteca.

| Versão | Suporte de segurança |
| --- | --- |
| Versão mais recente | Sim |
| Versões anteriores | Não |

## Credenciais de Banco de Dados

A MindQA DB Commons não exige que credenciais de banco de dados sejam armazenadas diretamente no código-fonte.

Nunca faça commit de usuários, senhas, tokens, strings de conexão contendo credenciais ou outras informações sensíveis no repositório.

Em ambientes de CI/CD, utilize os mecanismos de gerenciamento de secrets disponibilizados pela plataforma.

A biblioteca não realiza o gerenciamento ou armazenamento das credenciais utilizadas pelo projeto consumidor. O fornecimento, armazenamento e controle de acesso às credenciais são de responsabilidade do projeto que utiliza a biblioteca.

## Escopo e Responsabilidades

A MindQA DB Commons facilita a configuração e o acesso a bancos de dados em projetos de automação de testes, abstraindo parte da configuração necessária para utilização de diferentes bancos, bases e conexões.

A biblioteca fornece mecanismos para execução de operações em banco de dados, mas não substitui os controles de segurança da aplicação, da infraestrutura, do banco de dados ou da plataforma de CI/CD.

### O que a biblioteca não faz

A MindQA DB Commons:

- Não armazena usuários, senhas ou outras credenciais de banco de dados.
- Não fornece um gerenciador de secrets.
- Não criptografa ou descriptografa credenciais.
- Não cria usuários, permissões ou políticas de acesso no banco de dados.
- Não gerencia a segurança da infraestrutura ou do servidor de banco de dados.
- Não impede a execução de comandos SQL destrutivos, como `DELETE`, `UPDATE` ou outros comandos enviados pelo projeto consumidor.
- Não valida regras de negócio das queries executadas pelo usuário.
- Não substitui mecanismos de segurança fornecidos pelo banco de dados, pela infraestrutura ou pela plataforma de CI/CD.

O projeto consumidor é responsável por fornecer e proteger adequadamente suas credenciais, definir as permissões de acesso ao banco de dados e controlar quais comandos SQL podem ser executados.

Recomenda-se utilizar variáveis de ambiente ou mecanismos de gerenciamento de secrets da plataforma de CI/CD para informações sensíveis.

Também é recomendado conceder ao usuário de banco de dados somente as permissões necessárias para a execução dos testes, seguindo o princípio do menor privilégio.

## Como relatar uma vulnerabilidade

Não divulgue vulnerabilidades em issues, pull requests, discussões ou outros canais públicos.

Use o [relato privado de vulnerabilidade do GitHub](https://github.com/raialmeida/mindqa-db-commons/security/advisories/new).

Esse canal mantém o relato, a prova de conceito e a discussão privados enquanto a vulnerabilidade é analisada e uma eventual correção é preparada.

Inclua, quando possível:

- versão afetada da biblioteca;
- versão do Java;
- banco de dados utilizado;
- versão do driver JDBC;
- descrição do problema e seu possível impacto;
- passos mínimos e reproduzíveis;
- prova de conceito ou teste que demonstre a vulnerabilidade;
- possíveis formas de correção ou mitigação.

Remova senhas, tokens, strings de conexão, dados pessoais e qualquer outra informação confidencial antes de enviar o relato.
Prefira conteúdo em texto ou links privados a arquivos compactados.
O mantenedor confirmará o recebimento, avaliará o impacto e coordenará a correção e a publicação de uma nova versão quando necessário.
Aguarde a conclusão desse processo antes de divulgar publicamente os detalhes da vulnerabilidade.