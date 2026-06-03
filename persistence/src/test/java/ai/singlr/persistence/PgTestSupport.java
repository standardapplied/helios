/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.dbclient.DbClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.testcontainers.containers.PostgreSQLContainer;

/** Shared PostgreSQL test container and Helidon DbClient setup. */
final class PgTestSupport {

  private static final PostgreSQLContainer<?> CONTAINER =
      new PostgreSQLContainer<>("postgres:17-alpine");

  private static final DbClient DB_CLIENT;

  static {
    CONTAINER.start();
    DB_CLIENT = createDbClient();
    initSchema();
    initTriggers();
  }

  private PgTestSupport() {}

  static DbClient dbClient() {
    return DB_CLIENT;
  }

  static PgConfig pgConfig() {
    return PgConfig.newBuilder().withDbClient(DB_CLIENT).build();
  }

  static PgConfig pgConfig(String agentId) {
    return PgConfig.newBuilder().withDbClient(DB_CLIENT).withAgentId(agentId).build();
  }

  static void truncate() {
    dbClient().execute().dml("TRUNCATE TABLE helios_prompts");
  }

  static void truncateTraces() {
    dbClient().execute().dml("TRUNCATE TABLE helios_traces CASCADE");
    dbClient().execute().dml("TRUNCATE TABLE helios_annotations");
  }

  static void truncateMemory() {
    // CASCADE required because helios_messages.session_id REFERENCES helios_sessions(id).
    dbClient().execute().dml("TRUNCATE TABLE helios_sessions CASCADE");
    dbClient().execute().dml("TRUNCATE TABLE helios_archive");
    dbClient().execute().dml("TRUNCATE TABLE helios_core_blocks");
  }

  static void truncateRuntime() {
    // CASCADE required because helios_tool_calls.run_id REFERENCES helios_agent_runs(run_id).
    dbClient().execute().dml("TRUNCATE TABLE helios_agent_runs CASCADE");
  }

  /**
   * Generate a fresh run id and seed a minimal helios_agent_runs row for it. Tests that exercise
   * helios_tool_calls in isolation need this because the 1.4-era FK helios_tool_calls.run_id
   * REFERENCES helios_agent_runs(run_id) ON DELETE CASCADE rejects orphan tool-call inserts.
   */
  static java.util.UUID newSeededRunId() {
    var runId = ai.singlr.core.common.Ids.newId();
    seedRun(runId);
    return runId;
  }

  /** Insert a minimal RUNNING row into helios_agent_runs for {@code runId}. Idempotent. */
  static void seedRun(java.util.UUID runId) {
    var now = ai.singlr.core.common.Ids.now();
    dbClient()
        .execute()
        .dml(
            """
            INSERT INTO helios_agent_runs (
                run_id, agent_id, status, iteration,
                started_at, last_checkpoint_at)
            VALUES (CAST(? AS UUID), 'test-agent', 'RUNNING', 0, ?, ?)
            ON CONFLICT (run_id) DO NOTHING
            """,
            runId.toString(),
            now,
            now);
  }

  private static void initSchema() {
    try {
      // Drop existing helios_* tables CASCADE so each test JVM gets a fresh schema. Required
      // because schema.sql uses CREATE TABLE IF NOT EXISTS — it would skip existing tables that
      // carry old shapes (e.g. pre-1.4 helios_core_blocks.block_id NOT NULL, pre-1.4
      // helios_messages without ON DELETE CASCADE to helios_sessions). DROP CASCADE is safe in
      // the test database; production migrations are documented in CLAUDE.md.
      for (var table :
          List.of(
              "helios_tool_calls",
              "helios_agent_runs",
              "helios_messages",
              "helios_sessions",
              "helios_archive",
              "helios_core_blocks",
              "helios_traces",
              "helios_annotations",
              "helios_prompts")) {
        DB_CLIENT.execute().dml("DROP TABLE IF EXISTS " + table + " CASCADE");
      }
      var schema =
          new String(
              PgTestSupport.class.getResourceAsStream("schema.sql").readAllBytes(),
              StandardCharsets.UTF_8);
      for (var statement : schema.split(";")) {
        var trimmed = statement.strip();
        if (!trimmed.isEmpty()) {
          DB_CLIENT.execute().dml(trimmed);
        }
      }
    } catch (Exception e) {
      throw new RuntimeException("Failed to initialize schema", e);
    }
  }

  private static void initTriggers() {
    DB_CLIENT
        .execute()
        .dml(
            """
            CREATE OR REPLACE FUNCTION helios_update_feedback_counts()
            RETURNS TRIGGER AS $$
            BEGIN
              IF NEW.rating > 0 THEN
                UPDATE helios_traces SET thumbs_up_count = thumbs_up_count + 1
                  WHERE id = NEW.subject_id;
              ELSIF NEW.rating < 0 THEN
                UPDATE helios_traces SET thumbs_down_count = thumbs_down_count + 1
                  WHERE id = NEW.subject_id;
              END IF;
              RETURN NEW;
            END;
            $$ LANGUAGE plpgsql
            """);
    DB_CLIENT
        .execute()
        .dml(
            """
            CREATE OR REPLACE TRIGGER trg_helios_feedback_counts
              AFTER INSERT ON helios_annotations
              FOR EACH ROW
              WHEN (NEW.rating IS NOT NULL AND NEW.rating != 0)
              EXECUTE FUNCTION helios_update_feedback_counts()
            """);
  }

  private static DbClient createDbClient() {
    var config =
        Config.builder()
            .addSource(
                ConfigSources.create(
                    Map.of(
                        "source", "jdbc",
                        "connection.url", CONTAINER.getJdbcUrl(),
                        "connection.username", CONTAINER.getUsername(),
                        "connection.password", CONTAINER.getPassword())))
            .disableEnvironmentVariablesSource()
            .disableSystemPropertiesSource()
            .build();
    return DbClient.create(config);
  }
}
