/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.Response.Usage;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.StatusData;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link PgTraceStore#summarize} aggregates the stored runs by name or by model. */
class PgTraceRollupTest {

  private static final OffsetDateTime BASE =
      OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC);

  private final PgTraceStore store = new PgTraceStore(PgTestSupport.pgConfig());
  private final PgSpanExporter exporter = new PgSpanExporter(PgTestSupport.pgConfig());
  private final AtomicInteger runs = new AtomicInteger();

  @BeforeEach
  void setUp() {
    PgTestSupport.truncateTraces();
  }

  @Test
  void rollupByNameAggregatesCountsTokensAndCost() {
    seed("analytics", "model-x", BASE, 1000, null, Usage.of(100, 50, 20, 10), 1_000L);
    seed("analytics", "model-x", BASE, 2000, null, Usage.of(200, 100, 0, 0), 2_000L);
    seed("analytics", "model-y", BASE, 3000, "boom", Usage.of(10, 5, 0, 0), 500L);
    seed("matchmaking", "model-x", BASE, 4000, null, Usage.of(1, 1, 1, 1), 4L);

    var byName = index(store.summarize(TraceRollupKey.NAME, TraceFilter.none()), "name");

    assertEquals(2, byName.size());
    var analytics = byName.get("analytics");
    assertEquals(3, analytics.runCount());
    assertEquals(1, analytics.errorCount());
    assertEquals(2.0d / 3.0d, analytics.successRate(), 1e-9);
    assertEquals(310, analytics.inputTokens());
    assertEquals(155, analytics.outputTokens());
    assertEquals(20, analytics.cacheCreationTokens());
    assertEquals(10, analytics.cacheReadTokens());
    assertEquals(495, analytics.totalTokens());
    assertEquals(3_500L, analytics.costMicroUsd());
    assertEquals(2000, analytics.durationP50Millis());
    assertEquals(2900, analytics.durationP95Millis());
    var matchmaking = byName.get("matchmaking");
    assertEquals(1, matchmaking.runCount());
    assertEquals(0, matchmaking.errorCount());
    assertEquals(4L, matchmaking.costMicroUsd());
  }

  @Test
  void rollupByModelGroupsOnTheModelAndSkipsRunsWithNone() {
    seed("analytics", "model-x", BASE, 1000, null, Usage.of(10, 5), 100L);
    seed("matchmaking", "model-x", BASE, 1000, null, Usage.of(10, 5), 100L);
    seed("analytics", "model-y", BASE, 1000, null, Usage.of(30, 15), 300L);
    seed("analytics", null, BASE, 1000, null, Usage.of(1, 1), 1L);

    var byModel = index(store.summarize(TraceRollupKey.MODEL_ID, TraceFilter.none()), "modelId");

    assertEquals(2, byModel.size());
    assertEquals(2, byModel.get("model-x").runCount());
    assertEquals(20, byModel.get("model-x").inputTokens());
    assertEquals(1, byModel.get("model-y").runCount());
    assertEquals(30, byModel.get("model-y").inputTokens());
  }

  @Test
  void filterRestrictsByNameAndAWindowFromSinceUpToUntil() {
    seed("analytics", "model-x", BASE.minusNanos(1000), 1000, null, Usage.of(1, 1), 1L);
    seed("analytics", "model-x", BASE, 1000, null, Usage.of(10, 5), 100L);
    seed("analytics", "model-x", BASE.plusDays(7), 1000, null, Usage.of(90, 45), 900L);
    seed("matchmaking", "model-x", BASE, 1000, null, Usage.of(5, 5), 50L);

    var windowed =
        store.summarize(
            TraceRollupKey.MODEL_ID,
            TraceFilter.newBuilder()
                .withName("analytics")
                .withSince(BASE)
                .withUntil(BASE.plusDays(7))
                .build());

    assertEquals(1, windowed.size());
    assertEquals(1, windowed.getFirst().runCount());
    assertEquals(10, windowed.getFirst().inputTokens());
  }

  @Test
  void runsWithoutUsageContributeZeroToTokenSums() {
    seed("analytics", "model-x", BASE, 1000, null, null, null);

    var rollups = store.summarize(TraceRollupKey.NAME, TraceFilter.none());

    assertEquals(1, rollups.size());
    assertEquals(1, rollups.getFirst().runCount());
    assertEquals(0, rollups.getFirst().inputTokens());
    assertEquals(0, rollups.getFirst().costMicroUsd());
  }

  @Test
  void emptyTableYieldsEmptyRollup() {
    assertTrue(store.summarize(TraceRollupKey.NAME, TraceFilter.none()).isEmpty());
  }

  @Test
  void nullArgumentsThrow() {
    assertThrows(NullPointerException.class, () -> store.summarize(null, TraceFilter.none()));
    assertThrows(NullPointerException.class, () -> store.summarize(TraceRollupKey.NAME, null));
  }

  /** Store a run of {@code name} on {@code model}, as the session's run span describes it. */
  private void seed(
      String name,
      String model,
      OffsetDateTime start,
      long durationMillis,
      String error,
      Usage usage,
      Long costMicroUsd) {
    var index = runs.incrementAndGet();
    var attributes =
        Attributes.builder()
            .put("gen_ai.operation.name", "invoke_agent")
            .put("gen_ai.agent.name", name)
            .put("helios.run.id", "run-" + index);
    if (model != null) {
      attributes.put("gen_ai.request.model", model);
    }
    if (usage != null) {
      attributes
          .put(
              "gen_ai.usage.input_tokens",
              (long) usage.inputTokens()
                  + usage.cacheCreationInputTokens()
                  + usage.cacheReadInputTokens())
          .put("gen_ai.usage.output_tokens", (long) usage.outputTokens())
          .put("gen_ai.usage.cache_write.input_tokens", (long) usage.cacheCreationInputTokens())
          .put("gen_ai.usage.cache_read.input_tokens", (long) usage.cacheReadInputTokens())
          .put("helios.cost.micro_usd", costMicroUsd);
    }
    var started = nanos(start);
    var span =
        ExportedSpans.span("%016x".formatted(index), "invoke_agent " + name, 0, attributes.build())
            .setStartEpochNanos(started)
            .setEndEpochNanos(started + Duration.ofMillis(durationMillis).toNanos())
            .setStatus(
                error == null ? StatusData.unset() : StatusData.create(StatusCode.ERROR, error))
            .build();
    assertTrue(exporter.export(List.of(span)).isSuccess());
  }

  private static long nanos(OffsetDateTime at) {
    var instant = at.toInstant();
    return Math.addExact(
        Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L), instant.getNano());
  }

  private static Map<String, TraceRollup> index(List<TraceRollup> rollups, String dimension) {
    return rollups.stream().collect(Collectors.toMap(r -> r.key().get(dimension), r -> r));
  }
}
