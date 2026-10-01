/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence.mapper;

import ai.singlr.core.runtime.AgentRun;
import ai.singlr.core.runtime.AgentRunStatus;
import io.helidon.dbclient.DbRow;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/** Maps Helidon {@link DbRow} results to {@link AgentRun} records. */
public final class AgentRunMapper {

  private AgentRunMapper() {}

  /** Map a single row. */
  public static AgentRun map(DbRow row) {
    var iterObj = row.column("iteration").get(Object.class);
    int iteration = iterObj != null ? ((Number) iterObj).intValue() : 0;
    return AgentRun.newBuilder()
        .withRunId(row.column("run_id").get(UUID.class))
        .withSessionId(row.column("session_id").get(UUID.class))
        .withAgentId(row.column("agent_id").getString())
        .withUserId(row.column("user_id").getString())
        .withStatus(AgentRunStatus.valueOf(row.column("status").getString()))
        .withIteration(iteration)
        .withStartedAt(row.column("started_at").get(OffsetDateTime.class))
        .withLastCheckpointAt(row.column("last_checkpoint_at").get(OffsetDateTime.class))
        .withEndedAt(row.column("ended_at").get(OffsetDateTime.class))
        .withError(row.column("error").getString())
        .build();
  }

  /** Map a stream of rows to an immutable list. */
  public static List<AgentRun> mapAll(Stream<DbRow> rows) {
    return rows.map(AgentRunMapper::map).toList();
  }
}
