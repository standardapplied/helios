/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.persistence.mapper;

import com.standardapplied.helios.persistence.Span;
import io.helidon.dbclient.DbRow;
import java.time.OffsetDateTime;

/** Maps Helidon {@link DbRow} results to {@link Span} records. */
public final class SpanMapper {

  private SpanMapper() {}

  /** Maps a single database row to a Span. */
  public static Span map(DbRow row) {
    return Span.newBuilder()
        .withId(row.column("id").getString())
        .withName(row.column("name").getString())
        .withKind(row.column("kind").getString())
        .withStartTime(row.column("start_time").get(OffsetDateTime.class))
        .withEndTime(row.column("end_time").get(OffsetDateTime.class))
        .withError(row.column("error").getString())
        .withAttributes(JsonbMapper.fromJsonb(row.column("attributes").getString()))
        .withUsage(UsageMapper.usage(row))
        .withCost(UsageMapper.cost(row))
        .build();
  }
}
