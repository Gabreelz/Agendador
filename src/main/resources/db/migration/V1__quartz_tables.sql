-- V1__init.sql
-- Tabelas de domínio do Agendador de Tarefas Automáticas.
-- As tabelas QRTZ_* ficam em V2__quartz.sql.

CREATE TABLE app_user (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(120) NOT NULL,
    email         VARCHAR(160) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'OPERADOR' CHECK (role IN ('ADMIN','OPERADOR')),
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE email_template (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(120) NOT NULL UNIQUE,
    subject    VARCHAR(200) NOT NULL,
    body       TEXT         NOT NULL, -- aceita variáveis {{var}}
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE job (
    id                 BIGSERIAL PRIMARY KEY,
    name               VARCHAR(120) NOT NULL UNIQUE,
    description        VARCHAR(500),
    type               VARCHAR(20)  NOT NULL CHECK (type IN ('EMAIL_REMINDER','BACKUP')),
    cron_expression    VARCHAR(100) NOT NULL,
    timezone           VARCHAR(60)  NOT NULL DEFAULT 'America/Sao_Paulo',
    status             VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                       CHECK (status IN ('ACTIVE','PAUSED','DISABLED')),
    misfire_policy     VARCHAR(30)  NOT NULL DEFAULT 'FIRE_ONCE_NOW'
                       CHECK (misfire_policy IN ('FIRE_ONCE_NOW','SKIP')),
    max_retries        INT          NOT NULL DEFAULT 3 CHECK (max_retries >= 0),
    retry_backoff_secs INT          NOT NULL DEFAULT 60,
    timeout_secs       INT          NOT NULL DEFAULT 600,
    next_run_at        TIMESTAMPTZ,
    last_run_at        TIMESTAMPTZ,
    created_by         BIGINT REFERENCES app_user(id),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version            BIGINT       NOT NULL DEFAULT 0 -- @Version (lock otimista)
);
CREATE INDEX idx_job_status ON job(status);
CREATE INDEX idx_job_type   ON job(type);

CREATE TABLE email_job_config (
    job_id          BIGINT PRIMARY KEY REFERENCES job(id) ON DELETE CASCADE,
    to_addresses    TEXT NOT NULL, -- separados por vírgula
    cc_addresses    TEXT,
    bcc_addresses   TEXT,
    subject         VARCHAR(200),
    body            TEXT,
    template_id     BIGINT REFERENCES email_template(id),
    attachment_path VARCHAR(500),
    CHECK (template_id IS NOT NULL OR (subject IS NOT NULL AND body IS NOT NULL))
);

CREATE TABLE backup_job_config (
    job_id           BIGINT PRIMARY KEY REFERENCES job(id) ON DELETE CASCADE,
    source_type      VARCHAR(20) NOT NULL CHECK (source_type IN ('DIRECTORY','DATABASE')),
    source_path      VARCHAR(500), -- usado em DIRECTORY
    db_host          VARCHAR(160), -- usados em DATABASE
    db_port          INT,
    db_name          VARCHAR(120),
    db_username      VARCHAR(120),
    db_password_enc  TEXT,         -- criptografada (AES-256-GCM, ver T-29)
    destination_path VARCHAR(500) NOT NULL,
    compress         BOOLEAN NOT NULL DEFAULT TRUE,
    retention_days   INT NOT NULL DEFAULT 30 CHECK (retention_days > 0)
);

CREATE TABLE job_execution (
    id             BIGSERIAL PRIMARY KEY,
    job_id         BIGINT      NOT NULL REFERENCES job(id) ON DELETE CASCADE,
    scheduled_for  TIMESTAMPTZ NOT NULL,
    started_at     TIMESTAMPTZ,
    finished_at    TIMESTAMPTZ,
    status         VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                   CHECK (status IN ('PENDING','RUNNING','SUCCESS','FAILED','RETRYING','TIMEOUT','SKIPPED')),
    attempt        INT         NOT NULL DEFAULT 1,
    triggered_by   VARCHAR(20) NOT NULL DEFAULT 'SCHEDULER' CHECK (triggered_by IN ('SCHEDULER','MANUAL')),
    triggered_user BIGINT REFERENCES app_user(id),
    node_id        VARCHAR(80), -- instância que executou (cluster)
    result_summary VARCHAR(500),
    error_message  TEXT
);
CREATE INDEX idx_exec_job_started ON job_execution(job_id, started_at DESC);
CREATE INDEX idx_exec_status      ON job_execution(status);

CREATE TABLE job_execution_log (
    id           BIGSERIAL PRIMARY KEY,
    execution_id BIGINT      NOT NULL REFERENCES job_execution(id) ON DELETE CASCADE,
    level        VARCHAR(10) NOT NULL CHECK (level IN ('INFO','WARN','ERROR')),
    message      TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_log_execution ON job_execution_log(execution_id);

CREATE TABLE job_alert_recipient (
    id         BIGSERIAL PRIMARY KEY,
    job_id     BIGINT       NOT NULL REFERENCES job(id) ON DELETE CASCADE,
    email      VARCHAR(160) NOT NULL,
    on_failure BOOLEAN      NOT NULL DEFAULT TRUE,
    on_success BOOLEAN      NOT NULL DEFAULT FALSE,
    UNIQUE (job_id, email)
);

CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT REFERENCES app_user(id),
    entity_type VARCHAR(40) NOT NULL, -- JOB, TEMPLATE...
    entity_id   BIGINT      NOT NULL,
    action      VARCHAR(20) NOT NULL, -- CREATE, UPDATE, PAUSE, DELETE...
    details     JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_entity ON audit_log(entity_type, entity_id);