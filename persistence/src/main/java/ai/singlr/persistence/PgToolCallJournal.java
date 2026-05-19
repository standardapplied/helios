/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence;

import ai.singlr.core.common.Ids;
import ai.singlr.core.runtime.ToolCallJournal;
import ai.singlr.core.runtime.ToolCallRecord;
import ai.singlr.core.runtime.ToolCallStatus;
import ai.singlr.persistence.mapper.JsonbMapper;
import ai.singlr.persistence.mapper.ToolCallRecordMapper;
import ai.singlr.persistence.sql.ToolCallJournalSql;
import io.helidon.dbclient.DbClient;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * PostgreSQL-backed {@link ToolCallJournal} using Helidon DbClient.
 *
 * <p>Terminal transitions ({@code complete}/{@code fail}) only update rows still in {@link
 * ToolCallStatus#STARTED} — the WHERE clause includes {@code status = 'STARTED'}, so concurrent
 * terminal transitions cannot stomp each other and a "complete after fail" is a no-op rather than a
 * silent state corruption. Callers that need to know whether the transition actually applied can
 * read {@link io.helidon.dbclient.DbExecute#dml} return values; this implementation throws {@link
 * PgException} only on database errors.
 */
public class PgToolCallJournal implements ToolCallJournal {

  private final PgConfig config;
  private final DbClient dbClient;

  public PgToolCallJournal(PgConfig config) {
    this.config = Objects.requireNonNull(config, "config");
    this.dbClient = config.dbClient();
  }

  @Override
  public void start(ToolCallRecord record) {
    Objects.requireNonNull(record, "record");
    try {
      // Args serialise to JSON via JsonbMapper, then pass through config.redact() — the byte-level
      // redactor will scrub any registered secret value regardless of its JSON context (string
      // value, nested array, etc.). Output and error are text and redact directly.
      var argsJson =
          record.args() == null ? null : config.redact(JsonbMapper.objectToJsonb(record.args()));
      dbClient
          .execute()
          .dml(
              config.qualify(ToolCallJournalSql.INSERT),
              record.runId().toString(),
              record.toolCallId(),
              record.iteration(),
              record.toolName(),
              argsJson,
              record.status().name(),
              config.redact(record.output()),
              config.redact(record.error()),
              record.startedAt(),
              record.endedAt());
    } catch (Exception e) {
      throw new PgException(
          "Failed to insert tool-call journal entry for run " + record.runId(), e);
    }
  }

  @Override
  public void complete(UUID runId, String toolCallId, String output) {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(toolCallId, "toolCallId");
    try {
      dbClient
          .execute()
          .dml(
              config.qualify(ToolCallJournalSql.COMPLETE),
              ToolCallStatus.SUCCEEDED.name(),
              config.redact(output),
              Ids.now(),
              runId.toString(),
              toolCallId);
    } catch (Exception e) {
      throw new PgException(
          "Failed to complete tool-call journal entry " + toolCallId + " in run " + runId, e);
    }
  }

  @Override
  public void fail(UUID runId, String toolCallId, String error) {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(toolCallId, "toolCallId");
    try {
      dbClient
          .execute()
          .dml(
              config.qualify(ToolCallJournalSql.FAIL),
              ToolCallStatus.FAILED.name(),
              config.redact(error),
              Ids.now(),
              runId.toString(),
              toolCallId);
    } catch (Exception e) {
      throw new PgException(
          "Failed to mark tool-call journal entry " + toolCallId + " failed in run " + runId, e);
    }
  }

  @Override
  public List<ToolCallRecord> inflight(UUID runId) {
    if (runId == null) {
      return List.of();
    }
    try {
      return ToolCallRecordMapper.mapAll(
          dbClient
              .execute()
              .query(config.qualify(ToolCallJournalSql.FIND_INFLIGHT), runId.toString()));
    } catch (Exception e) {
      throw new PgException("Failed to find in-flight tool calls for run " + runId, e);
    }
  }

  @Override
  public List<ToolCallRecord> all(UUID runId) {
    if (runId == null) {
      return List.of();
    }
    try {
      return ToolCallRecordMapper.mapAll(
          dbClient.execute().query(config.qualify(ToolCallJournalSql.FIND_ALL), runId.toString()));
    } catch (Exception e) {
      throw new PgException("Failed to load tool-call journal for run " + runId, e);
    }
  }
}
