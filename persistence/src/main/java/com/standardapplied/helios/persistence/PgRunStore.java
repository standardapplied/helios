/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence;

import ai.singlr.core.common.Ids;
import ai.singlr.core.runtime.AgentRun;
import ai.singlr.core.runtime.AgentRunStatus;
import ai.singlr.core.runtime.RunStore;
import ai.singlr.persistence.mapper.AgentRunMapper;
import ai.singlr.persistence.sql.AgentRunSql;
import io.helidon.dbclient.DbClient;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL-backed {@link RunStore} using Helidon DbClient. Upserts on every {@code checkpoint}
 * call so the table always reflects the most recent iteration of each run.
 *
 * <p>Designed so v2 distributed-coordination columns ({@code worker_id}, {@code lease_until}) can
 * be added additively without breaking single-JVM users — the column list in {@link
 * AgentRunSql#UPSERT} is explicit and new columns get defaults from the table definition.
 */
public class PgRunStore implements RunStore {

  private final PgConfig config;
  private final DbClient dbClient;

  public PgRunStore(PgConfig config) {
    this.config = Objects.requireNonNull(config, "config");
    this.dbClient = config.dbClient();
  }

  @Override
  public void checkpoint(AgentRun run) {
    Objects.requireNonNull(run, "run");
    try {
      dbClient
          .execute()
          .dml(
              config.qualify(AgentRunSql.UPSERT),
              run.runId().toString(),
              run.sessionId() == null ? null : run.sessionId().toString(),
              run.agentId(),
              run.userId(),
              run.status().name(),
              run.iteration(),
              run.startedAt(),
              run.lastCheckpointAt(),
              run.endedAt(),
              run.error());
    } catch (Exception e) {
      throw new PgException("Failed to checkpoint agent run: " + run.runId(), e);
    }
  }

  @Override
  public Optional<AgentRun> find(UUID runId) {
    if (runId == null) {
      return Optional.empty();
    }
    try {
      return dbClient
          .execute()
          .query(config.qualify(AgentRunSql.FIND_BY_ID), runId.toString())
          .map(AgentRunMapper::map)
          .findFirst();
    } catch (Exception e) {
      throw new PgException("Failed to find agent run: " + runId, e);
    }
  }

  @Override
  public List<AgentRun> findByStatus(AgentRunStatus status) {
    Objects.requireNonNull(status, "status");
    try {
      return AgentRunMapper.mapAll(
          dbClient.execute().query(config.qualify(AgentRunSql.FIND_BY_STATUS), status.name()));
    } catch (Exception e) {
      throw new PgException("Failed to list agent runs by status: " + status, e);
    }
  }

  /**
   * Delete terminal runs older than {@code olderThan}. Tool-call rows cascade automatically via the
   * {@code helios_tool_calls.run_id REFERENCES helios_agent_runs(run_id) ON DELETE CASCADE}
   * constraint declared in {@code schema.sql} (1.4-era migration) — the prior application-side
   * two-step purge is gone.
   */
  @Override
  public int purgeOlderThan(Duration olderThan) {
    Objects.requireNonNull(olderThan, "olderThan");
    if (olderThan.isNegative()) {
      throw new IllegalArgumentException("olderThan must be non-negative");
    }
    var cutoff = Ids.now().minus(olderThan);
    try {
      var deletedRuns =
          dbClient
              .execute()
              .dml(config.qualify(AgentRunSql.PURGE_TERMINAL_RUNS_OLDER_THAN), cutoff);
      return Math.toIntExact(deletedRuns);
    } catch (ArithmeticException overflow) {
      throw new PgException("purgeOlderThan rowcount overflowed Integer.MAX_VALUE", overflow);
    } catch (Exception e) {
      throw new PgException("Failed to purge agent runs older than " + olderThan, e);
    }
  }
}
