/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.persistence;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.dbclient.DbClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
    DB_CLIENT = dbClient(CONTAINER.getJdbcUrl(), CONTAINER.getUsername(), CONTAINER.getPassword());
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

  static void truncate() {
    dbClient().execute().dml("TRUNCATE TABLE helios_prompts");
  }

  static void truncateTraces() {
    dbClient().execute().dml("TRUNCATE TABLE helios_traces CASCADE");
    dbClient().execute().dml("TRUNCATE TABLE helios_annotations");
  }

  static void truncateSessions() {
    dbClient().execute().dml("TRUNCATE TABLE helios_session_entries");
  }

  private static void initSchema() {
    try {
      // Drop existing helios_* tables CASCADE so each test JVM gets a fresh schema. Required
      // because schema.sql uses CREATE TABLE IF NOT EXISTS — it would skip existing tables that
      // carry old shapes. DROP CASCADE is safe in the test database; production migrations are
      // documented in CHANGELOG.md.
      for (var table :
          List.of(
              "helios_session_entries",
              "helios_spans",
              "helios_traces",
              "helios_annotations",
              "helios_prompts")) {
        DB_CLIENT.execute().dml("DROP TABLE IF EXISTS " + table + " CASCADE");
      }
      schemaStatements().forEach(DB_CLIENT.execute()::dml);
    } catch (Exception e) {
      throw new RuntimeException("Failed to initialize schema", e);
    }
  }

  /** The statements of {@code schema.sql}, in order. */
  static List<String> schemaStatements() throws IOException {
    try (var schema = PgTestSupport.class.getResourceAsStream("schema.sql")) {
      return Arrays.stream(new String(schema.readAllBytes(), StandardCharsets.UTF_8).split(";"))
          .map(String::strip)
          .filter(statement -> !statement.isEmpty())
          .toList();
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

  static DbClient dbClient(String url, String username, String password) {
    var config =
        Config.builder()
            .addSource(
                ConfigSources.create(
                    Map.of(
                        "source", "jdbc",
                        "connection.url", url,
                        "connection.username", username,
                        "connection.password", password)))
            .disableEnvironmentVariablesSource()
            .disableSystemPropertiesSource()
            .build();
    return DbClient.create(config);
  }
}
