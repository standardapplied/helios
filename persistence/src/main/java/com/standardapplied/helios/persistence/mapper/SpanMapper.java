/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence.mapper;

import ai.singlr.core.trace.Span;
import ai.singlr.core.trace.SpanKind;
import io.helidon.dbclient.DbRow;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Maps Helidon {@link DbRow} results to {@link Span} records. */
public final class SpanMapper {

  private SpanMapper() {}

  /**
   * Maps a single database row to a flat Span (no children).
   *
   * <p>Children are assembled via tree reconstruction in PgTraceStore.
   */
  public static Span map(DbRow row) {
    var attributes = JsonbMapper.fromJsonb(row.column("attributes").getString());

    return Span.newBuilder()
        .withId(row.column("id").get(UUID.class))
        .withName(row.column("name").getString())
        .withKind(SpanKind.valueOf(row.column("kind").getString()))
        .withStartTime(row.column("start_time").get(OffsetDateTime.class))
        .withEndTime(row.column("end_time").get(OffsetDateTime.class))
        .withError(row.column("error").getString())
        .withAttributes(attributes)
        .withUsage(UsageMapper.usage(row))
        .withCost(UsageMapper.cost(row))
        .build();
  }

  /**
   * Reads the parent_id column from a span row.
   *
   * @return the parent UUID, or null if this is a top-level span
   */
  public static UUID parentId(DbRow row) {
    return row.column("parent_id").get(UUID.class);
  }
}
