/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.persistence.mapper;

import com.standardapplied.helios.persistence.Trace;
import io.helidon.dbclient.DbRow;
import java.time.OffsetDateTime;

/** Maps Helidon {@link DbRow} results to {@link Trace} records. */
public final class TraceMapper {

  private TraceMapper() {}

  /** Maps a single database row to a Trace without its spans, which PgTraceStore reads apart. */
  public static Trace map(DbRow row) {
    return Trace.newBuilder()
        .withId(row.column("id").getString())
        .withOtelTraceId(row.column("otel_trace_id").getString())
        .withOtelSpanId(row.column("otel_span_id").getString())
        .withName(row.column("name").getString())
        .withStartTime(row.column("start_time").get(OffsetDateTime.class))
        .withEndTime(row.column("end_time").get(OffsetDateTime.class))
        .withError(row.column("error").getString())
        .withAttributes(JsonbMapper.fromJsonb(row.column("attributes").getString()))
        .withInputText(row.column("input_text").getString())
        .withOutputText(row.column("output_text").getString())
        .withSessionId(row.column("session_id").getString())
        .withModelId(row.column("model_id").getString())
        .withTotalTokens(row.column("total_tokens").getInt())
        .withUsage(UsageMapper.usage(row))
        .withCost(UsageMapper.cost(row))
        .withThumbsUpCount(row.column("thumbs_up_count").getInt())
        .withThumbsDownCount(row.column("thumbs_down_count").getInt())
        .build();
  }
}
