# scheduler-app

Projeto base em **Java 21 + Spring Boot 3.5** com PostgreSQL 16, Flyway, Quartz (JDBC store), Spring Mail e Spring Security.
Ambiente local reproduzível via Docker Compose (PostgreSQL + MailHog).

## Pré-requisitos

| Ferramenta | Versão | Observação |
|---|---|---|
| JDK | 21 | `java -version` deve mostrar 21 |
| Docker + Docker Compose v2 | recente | `docker compose version` |
| Git | qualquer | |

Maven **não** precisa estar instalado: o projeto usa o Maven Wrapper (`./mvnw`).

## Início rápido

```bash
# 1. Variáveis de ambiente locais (arquivo ignorado pelo Git)
cp .env.example .env
#    edite .env e defina DB_PASSWORD e APP_ADMIN_PASSWORD

# 2. Infraestrutura (PostgreSQL 16 + MailHog)
docker compose up -d
docker compose ps            # postgres deve aparecer como "healthy"

# 3. Aplicação (profile dev é o padrão)
./mvnw spring-boot:run
```

No Windows use `mvnw.cmd spring-boot:run`.

### Verificando

```bash
curl http://localhost:8080/actuator/health          # {"status":"UP", ...}
curl -u admin:<APP_ADMIN_PASSWORD> http://localhost:8080/actuator/info
```

- Interface do MailHog (e-mails capturados): <http://localhost:8025>
- SMTP do MailHog: `localhost:1025`
- No log de inicialização você deve ver o Flyway aplicando `V1__quartz_tables` e o Quartz iniciando.

### Parar / limpar

```bash
docker compose down        # mantém os dados
docker compose down -v     # remove também o volume do banco
```

## Variáveis de ambiente

Nenhum segredo está versionado. Em **dev**, a aplicação lê o arquivo `.env` automaticamente
(`application-dev.yml`). Em **test/prod**, defina as variáveis no ambiente/orquestrador.

| Variável | Obrigatória | Padrão | Descrição |
|---|---|---|---|
| `DB_USER` | sim | – | Usuário do PostgreSQL |
| `DB_PASSWORD` | sim | – | Senha do PostgreSQL |
| `DB_HOST` | não | `localhost` | Host do banco |
| `DB_PORT` | não | `5432` | Porta do banco |
| `DB_NAME` | não | `scheduler` | Nome do banco |
| `DB_POOL_SIZE` | não | `10` | Tamanho do pool (prod) |
| `APP_ADMIN_USER` | sim | – | Usuário administrador da API (HTTP Basic) |
| `APP_ADMIN_PASSWORD` | sim | – | Senha do administrador |
| `MAIL_HOST` | dev: não / prod: sim | `localhost` | Servidor SMTP |
| `MAIL_PORT` | não | `1025` (dev) / `587` (prod) | Porta SMTP |
| `MAIL_USERNAME` | prod | – | Usuário SMTP |
| `MAIL_PASSWORD` | prod | – | Senha SMTP |
| `SPRING_PROFILES_ACTIVE` | não | `dev` | `dev`, `test` ou `prod` |

## Profiles

| Profile | Uso | Particularidades |
|---|---|---|
| `dev` | máquina local | importa `.env`, logs `DEBUG`, SMTP sem auth (MailHog) |
| `test` | `./mvnw verify` | Testcontainers (PostgreSQL), Quartz desligado, credenciais aleatórias |
| `prod` | produção | SMTP com STARTTLS+auth, health sem detalhes, todas as variáveis obrigatórias via ambiente |

Exemplo em produção:

```bash
export SPRING_PROFILES_ACTIVE=prod DB_HOST=... DB_USER=... DB_PASSWORD=... \
       APP_ADMIN_USER=... APP_ADMIN_PASSWORD=... MAIL_HOST=... MAIL_USERNAME=... MAIL_PASSWORD=...
java -jar target/scheduler-app-0.0.1-SNAPSHOT.jar
```

## Testes

```bash
./mvnw verify      # requer Docker em execução (Testcontainers)
```

## Estrutura de pacotes (`com.example.scheduler`)

| Pacote | Responsabilidade |
|---|---|
| `config` | Beans e configurações (segurança etc.) |
| `controller` | Endpoints REST |
| `service` | Regras de negócio e transações |
| `executor` | Lógica das tarefas executadas pelos jobs |
| `quartz` | Jobs, triggers e listeners do Quartz |
| `domain` | Entidades JPA |
| `repository` | Repositórios Spring Data |
| `dto` / `mapper` | Contratos da API e conversão |
| `exception` | Exceções de negócio e `@RestControllerAdvice` |

Migrations ficam em `src/main/resources/db/migration` (`V<n>__descricao.sql`, imutáveis após merge).

## Problemas comuns

- **`Could not resolve placeholder 'DB_PASSWORD'`**: o `.env` não existe ou não está na raiz onde você executa o comando.
- **`password authentication failed`** após trocar `DB_PASSWORD`: o volume antigo mantém a senha anterior; rode `docker compose down -v`.
- **Porta 5432 ocupada**: altere `DB_PORT` no `.env`.
- **MailHog em Apple Silicon**: a imagem roda emulada (`platform: linux/amd64`); um aviso é normal.

## Contribuindo

Veja [CONTRIBUTING.md](CONTRIBUTING.md) (convenção de commits, branches e migrations).
