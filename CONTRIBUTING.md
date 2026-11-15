# Contribuindo

## Convenção de commits

Seguimos o [Conventional Commits 1.0](https://www.conventionalcommits.org/pt-br/v1.0.0/):

```
<tipo>(<escopo opcional>): <descrição no imperativo>

[corpo opcional]

[rodapé opcional]
```

| Tipo       | Uso                                                |
|------------|----------------------------------------------------|
| `feat`     | nova funcionalidade                                |
| `fix`      | correção de bug                                    |
| `docs`     | somente documentação                               |
| `refactor` | mudança de código sem alterar comportamento        |
| `test`     | criação/ajuste de testes                           |
| `build`    | build, dependências, Docker                        |
| `ci`       | pipelines                                          |
| `chore`    | tarefas diversas (sem impacto em código de produção)|
| `perf`     | melhoria de desempenho                             |
| `revert`   | reversão de commit                                 |

Escopos sugeridos: `quartz`, `mail`, `security`, `db`, `api`, `executor`, `config`.

Exemplos:

```
feat(quartz): agenda job de envio de e-mails diários
fix(db): corrige índice duplicado na migration V3
feat(api)!: renomeia campo "name" para "title" em /tasks

BREAKING CHANGE: clientes devem usar "title".
```

Ative o template de mensagem localmente:

```bash
git config commit.template .gitmessage
```

## Branches e PRs

- `main` sempre estável; trabalhe em `feat/<resumo>`, `fix/<resumo>`, `chore/<resumo>`.
- PRs pequenos, com testes (`./mvnw verify`) passando.
- Migrations Flyway **nunca** são editadas depois de mergeadas: crie uma nova `V<n>__descricao.sql`.

## Segredos

Nunca versione senhas, tokens ou chaves. Use variáveis de ambiente (ver README).
