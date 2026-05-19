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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
      // Args are walked pre-serialise and each string leaf is redacted before Jackson sees it.
      // This is the safe shape that PgTraceStore.redactValues already uses for span attributes;
      // running the byte-level Redactor over Jackson's output would miss secrets whose bytes get
      // escaped during serialisation. SecretRegistry also refuses JSON-unsafe bytes at
      // registration so post-serialise scrubbing of plain text fields stays correct, but
      // structured args go through the deep walk for clarity rather than relying on the
      // registration constraint alone.
      var argsJson =
          record.args() == null ? null : JsonbMapper.objectToJsonb(redactArgs(record.args()));
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

  /**
   * Walk {@code args} and apply {@link PgConfig#redact(String)} to every string leaf, returning a
   * new structure with the same shape. Non-string leaves (numbers, booleans, null) pass through
   * unchanged. The walk preserves {@link Map} and {@link List} positions and produces fresh
   * collections; the input is never mutated. Returns {@code args} unchanged when no redactor is
   * configured so the default no-op path stays allocation-free.
   */
  private Map<String, Object> redactArgs(Map<String, Object> args) {
    if (config.redactor() == null) {
      return args;
    }
    return redactMap(args);
  }

  private Map<String, Object> redactMap(Map<String, Object> m) {
    var out = new LinkedHashMap<String, Object>(m.size());
    for (var e : m.entrySet()) {
      out.put(e.getKey(), redactValue(e.getValue()));
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private Object redactValue(Object v) {
    return switch (v) {
      case null -> null;
      case String s -> config.redact(s);
      case Map<?, ?> nested -> redactMap((Map<String, Object>) nested);
      case List<?> list -> redactList(list);
      default -> v;
    };
  }

  private List<Object> redactList(List<?> list) {
    var out = new ArrayList<Object>(list.size());
    for (var v : list) {
      out.add(redactValue(v));
    }
    return out;
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
