/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence.mapper;

import ai.singlr.core.model.Message;
import ai.singlr.core.model.Role;
import io.helidon.dbclient.DbRow;
import java.util.List;
import java.util.stream.Stream;

/** Maps Helidon {@link DbRow} results to {@link Message} records. */
public final class MessageMapper {

  private MessageMapper() {}

  /** Maps a single database row to a Message. */
  public static Message map(DbRow row) {
    return Message.newBuilder()
        .withRole(Role.valueOf(row.column("role").getString()))
        .withContent(row.column("content").getString())
        .withToolCalls(JsonbMapper.toolCallsFromJsonb(row.column("tool_calls").getString()))
        .withToolCallId(row.column("tool_call_id").getString())
        .withToolName(row.column("tool_name").getString())
        .withMetadata(JsonbMapper.fromJsonb(row.column("metadata").getString()))
        .build();
  }

  /** Maps a stream of database rows to an immutable list of Messages. */
  public static List<Message> mapAll(Stream<DbRow> rows) {
    return rows.map(MessageMapper::map).toList();
  }
}
