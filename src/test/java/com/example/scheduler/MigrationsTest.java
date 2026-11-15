package com.example.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

class MigrationsTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static JdbcTemplate jdbc;
    static Flyway flyway;
    static MigrateResult firstRun;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(ds);
        flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load();
        firstRun = flyway.migrate();
    }

    // ---------- aplicação das migrations ----------

    @Test
    void appliesAllMigrationsOnEmptyDatabase() {
        assertThat(firstRun.success).isTrue();
        assertThat(firstRun.migrationsExecuted).isEqualTo(2);
        assertThat(flyway.info().applied())
                .extracting(i -> i.getVersion().getVersion(), i -> i.getState())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("1", MigrationState.SUCCESS),
                        org.assertj.core.groups.Tuple.tuple("2", MigrationState.SUCCESS));
    }

    @Test
    void runningMigrateAgainDoesNotReapplyAnything() {
        MigrateResult second = flyway.migrate();
        assertThat(second.migrationsExecuted).isZero();
        assertThat(flyway.info().pending()).isEmpty();
        flyway.validate(); // checksums continuam iguais
    }

    // ---------- estrutura ----------

    @Test
    void createsAllDomainAndQuartzTables() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);
        assertThat(tables).contains(
                "app_user", "email_template", "job", "email_job_config", "backup_job_config",
                "job_execution", "job_execution_log", "job_alert_recipient", "audit_log",
                "qrtz_job_details", "qrtz_triggers", "qrtz_simple_triggers", "qrtz_cron_triggers",
                "qrtz_simprop_triggers", "qrtz_blob_triggers", "qrtz_calendars",
                "qrtz_paused_trigger_grps", "qrtz_fired_triggers", "qrtz_scheduler_state", "qrtz_locks");
    }

    @Test
    void createsExpectedIndexes() {
        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'", String.class);
        assertThat(indexes).contains(
                "idx_job_status", "idx_job_type", "idx_exec_job_started", "idx_exec_status",
                "idx_log_execution", "idx_audit_entity");
    }

    @Test
    void jobExecutionIndexIsOrderedByStartedAtDesc() {
        String def = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_exec_job_started'", String.class);
        assertThat(def).contains("job_id").contains("started_at DESC");
    }

    @Test
    void createsUniqueConstraints() {
        List<String> uniques = jdbc.queryForList("""
                SELECT tc.table_name || '.' || kcu.column_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema
                WHERE tc.constraint_type = 'UNIQUE' AND tc.table_schema = 'public'
                """, String.class);
        assertThat(uniques).contains(
                "app_user.email", "email_template.name", "job.name", "job_alert_recipient.email");
    }

    @Test
    void createsForeignKeysWithCascadeWhereExpected() {
        List<String> cascades = jdbc.queryForList("""
                SELECT conrelid::regclass::text || '->' || confrelid::regclass::text
                FROM pg_constraint
                WHERE contype = 'f' AND confdeltype = 'c'
                  AND connamespace = 'public'::regnamespace
                """, String.class);
        assertThat(cascades).contains(
                "email_job_config->job", "backup_job_config->job", "job_execution->job",
                "job_execution_log->job_execution", "job_alert_recipient->job");
    }

    // ---------- constraints em ação ----------

    @Test
    void rejectsInvalidEnumValuesAndNegativeNumbers() {
        assertThatThrownBy(() -> insertJob("bad-type", "NOPE", "ACTIVE", 3))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertJob("bad-status", "BACKUP", "NOPE", 3))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertJob("bad-retries", "BACKUP", "ACTIVE", -1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void enforcesUniqueJobName() {
        insertJob("unique-name", "BACKUP", "ACTIVE", 3);
        assertThatThrownBy(() -> insertJob("unique-name", "BACKUP", "ACTIVE", 3))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void emailConfigRequiresTemplateOrSubjectAndBody() {
        long jobId = insertJob("email-check", "EMAIL_REMINDER", "ACTIVE", 3);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO email_job_config (job_id, to_addresses) VALUES (?, 'a@b.com')", jobId))
                .isInstanceOf(DataIntegrityViolationException.class);
        int rows = jdbc.update(
                "INSERT INTO email_job_config (job_id, to_addresses, subject, body) VALUES (?, 'a@b.com', 's', 'b')",
                jobId);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void appliesDefaultsToNewJob() {
        long id = insertJob("defaults", "BACKUP", "ACTIVE", 3);
        var row = jdbc.queryForMap(
                "SELECT timezone, misfire_policy, timeout_secs, retry_backoff_secs, version FROM job WHERE id = ?", id);
        assertThat(row).containsEntry("timezone", "America/Sao_Paulo")
                .containsEntry("misfire_policy", "FIRE_ONCE_NOW")
                .containsEntry("timeout_secs", 600)
                .containsEntry("retry_backoff_secs", 60)
                .containsEntry("version", 0L);
    }

    @Test
    void deletingJobCascadesToConfigExecutionsAndLogs() {
        long jobId = insertJob("cascade", "BACKUP", "ACTIVE", 3);
        jdbc.update("""
                INSERT INTO backup_job_config (job_id, source_type, source_path, destination_path)
                VALUES (?, 'DIRECTORY', '/data', '/backups')""", jobId);
        long execId = jdbc.queryForObject(
                "INSERT INTO job_execution (job_id, scheduled_for) VALUES (?, now()) RETURNING id", Long.class, jobId);
        jdbc.update("INSERT INTO job_execution_log (execution_id, level, message) VALUES (?, 'INFO', 'ok')", execId);
        jdbc.update("INSERT INTO job_alert_recipient (job_id, email) VALUES (?, 'x@y.com')", jobId);

        jdbc.update("DELETE FROM job WHERE id = ?", jobId);

        for (String table : List.of("backup_job_config", "job_alert_recipient")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE job_id = ?", Integer.class, jobId))
                    .as(table).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_execution WHERE id = ?", Integer.class, execId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM job_execution_log WHERE execution_id = ?", Integer.class, execId)).isZero();
    }

    @Test
    void rejectsInvalidExecutionStatusAndLogLevel() {
        long jobId = insertJob("exec-check", "BACKUP", "ACTIVE", 3);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO job_execution (job_id, scheduled_for, status) VALUES (?, now(), 'NOPE')", jobId))
                .isInstanceOf(DataIntegrityViolationException.class);
        long execId = jdbc.queryForObject(
                "INSERT INTO job_execution (job_id, scheduled_for) VALUES (?, now()) RETURNING id", Long.class, jobId);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO job_execution_log (execution_id, level, message) VALUES (?, 'DEBUG', 'x')", execId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void auditLogAcceptsJsonbDetails() {
        jdbc.update("""
                INSERT INTO audit_log (entity_type, entity_id, action, details)
                VALUES ('JOB', 1, 'CREATE', '{"before":null,"after":{"name":"x"}}'::jsonb)""");
        String name = jdbc.queryForObject(
                "SELECT details->'after'->>'name' FROM audit_log WHERE entity_type='JOB' AND entity_id=1", String.class);
        assertThat(name).isEqualTo("x");
    }

    private static long insertJob(String name, String type, String status, int maxRetries) {
        return jdbc.queryForObject("""
                INSERT INTO job (name, type, cron_expression, status, max_retries)
                VALUES (?, ?, '0 0 8 ? * MON-FRI', ?, ?) RETURNING id""",
                Long.class, name, type, status, maxRetries);
    }
}