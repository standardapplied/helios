/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence.mapper;

import ai.singlr.core.common.Strings;
import ai.singlr.core.runtime.ToolCallRecord;
import ai.singlr.core.runtime.ToolCallStatus;
import io.helidon.dbclient.DbRow;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/** Maps Helidon {@link DbRow} results to {@link ToolCallRecord} records. */
public final class ToolCallRecordMapper {

  private ToolCallRecordMapper() {}

  /** Map a single row. */
  public static ToolCallRecord map(DbRow row) {
    var argsJson = row.column("args").getString();
    Map<String, Object> args = null;
    if (!Strings.isBlank(argsJson)) {
      var parsed = JsonbMapper.fromJsonbObject(argsJson);
      args = parsed.isEmpty() ? null : parsed;
    }
    var iterObj = row.column("iteration").get(Object.class);
    int iteration = iterObj != null ? ((Number) iterObj).intValue() : 0;
    return ToolCallRecord.newBuilder()
        .withRunId(row.column("run_id").get(UUID.class))
        .withIteration(iteration)
        .withToolCallId(row.column("tool_call_id").getString())
        .withToolName(row.column("tool_name").getString())
        .withArgs(args)
        .withStatus(ToolCallStatus.valueOf(row.column("status").getString()))
        .withOutput(row.column("output").getString())
        .withError(row.column("error").getString())
        .withStartedAt(row.column("started_at").get(OffsetDateTime.class))
        .withEndedAt(row.column("ended_at").get(OffsetDateTime.class))
        .build();
  }

  /** Map a stream of rows to an immutable list. */
  public static List<ToolCallRecord> mapAll(Stream<DbRow> rows) {
    return rows.map(ToolCallRecordMapper::map).toList();
  }
}
