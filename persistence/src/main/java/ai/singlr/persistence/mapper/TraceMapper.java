/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence.mapper;

import ai.singlr.core.trace.Trace;
import io.helidon.dbclient.DbRow;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Maps Helidon {@link DbRow} results to {@link Trace} records. */
public final class TraceMapper {

  private TraceMapper() {}

  /**
   * Maps a single database row to a Trace (without spans).
   *
   * <p>Spans are assembled separately via tree reconstruction in PgTraceStore.
   */
  public static Trace map(DbRow row) {
    var attributes = JsonbMapper.fromJsonb(row.column("attributes").getString());
    var labels = JsonbMapper.listFromJsonb(row.column("labels").getString());

    var promptVersionObj = row.column("prompt_version").get(Object.class);
    Integer promptVersion =
        promptVersionObj != null ? ((Number) promptVersionObj).intValue() : null;

    var totalTokensObj = row.column("total_tokens").get(Object.class);
    int totalTokens = totalTokensObj != null ? ((Number) totalTokensObj).intValue() : 0;

    var thumbsUpObj = row.column("thumbs_up_count").get(Object.class);
    int thumbsUpCount = thumbsUpObj != null ? ((Number) thumbsUpObj).intValue() : 0;

    var thumbsDownObj = row.column("thumbs_down_count").get(Object.class);
    int thumbsDownCount = thumbsDownObj != null ? ((Number) thumbsDownObj).intValue() : 0;

    return Trace.newBuilder()
        .withId(row.column("id").get(UUID.class))
        .withName(row.column("name").getString())
        .withStartTime(row.column("start_time").get(OffsetDateTime.class))
        .withEndTime(row.column("end_time").get(OffsetDateTime.class))
        .withError(row.column("error").getString())
        .withAttributes(attributes)
        .withInputText(row.column("input_text").getString())
        .withOutputText(row.column("output_text").getString())
        .withUserId(row.column("user_id").getString())
        .withSessionId(row.column("session_id").get(UUID.class))
        .withModelId(row.column("model_id").getString())
        .withPromptName(row.column("prompt_name").getString())
        .withPromptVersion(promptVersion)
        .withTotalTokens(totalTokens)
        .withUsage(UsageMapper.usage(row))
        .withCost(UsageMapper.cost(row))
        .withThumbsUpCount(thumbsUpCount)
        .withThumbsDownCount(thumbsDownCount)
        .withGroupId(row.column("group_id").getString())
        .withLabels(labels)
        .build();
  }
}
