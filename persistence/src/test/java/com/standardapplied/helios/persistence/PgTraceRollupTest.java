/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.CostEstimate;
import ai.singlr.core.model.Response.Usage;
import ai.singlr.core.trace.Trace;
import ai.singlr.core.trace.TraceFilter;
import ai.singlr.core.trace.TraceRollup;
import ai.singlr.core.trace.TraceRollupKey;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PgTraceRollupTest {

  private static final OffsetDateTime BASE =
      OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC);

  private PgTraceStore store;

  @BeforeEach
  void setUp() {
    PgTestSupport.truncateTraces();
    store = new PgTraceStore(PgTestSupport.pgConfig());
  }

  @Test
  void rollupByGroupIdAggregatesCountsTokensAndCost() {
    seed("batch-a", "analytics", 1, 1000, null, Usage.of(100, 50, 20, 10), 1_000L);
    seed("batch-a", "analytics", 1, 2000, null, Usage.of(200, 100, 0, 0), 2_000L);
    seed("batch-a", "analytics", 1, 3000, "boom", Usage.of(10, 5, 0, 0), 500L);
    seed("batch-b", "analytics", 2, 4000, null, Usage.of(1, 1, 1, 1), 4L);
    seedWithoutGroup();

    var rollups = store.summarize(TraceRollupKey.GROUP_ID, TraceFilter.none());

    assertEquals(2, rollups.size());
    var byKey = index(rollups, "groupId");
    var a = byKey.get("batch-a");
    assertEquals(3, a.runCount());
    assertEquals(1, a.errorCount());
    assertEquals(2.0d / 3.0d, a.successRate(), 1e-9);
    assertEquals(310, a.inputTokens());
    assertEquals(155, a.outputTokens());
    assertEquals(20, a.cacheCreationTokens());
    assertEquals(10, a.cacheReadTokens());
    assertEquals(495, a.totalTokens());
    assertEquals(3_500L, a.costMicroUsd());
    assertEquals(2000, a.durationP50Millis());
    assertEquals(2900, a.durationP95Millis());

    var b = byKey.get("batch-b");
    assertEquals(1, b.runCount());
    assertEquals(0, b.errorCount());
    assertEquals(4L, b.costMicroUsd());
  }

  @Test
  void rollupByPromptGroupsOnNameAndVersion() {
    seed("batch-a", "analytics", 1, 1000, null, Usage.of(10, 5), 100L);
    seed("batch-b", "analytics", 1, 1000, null, Usage.of(10, 5), 100L);
    seed("batch-a", "analytics", 2, 1000, null, Usage.of(30, 15), 300L);

    var rollups = store.summarize(TraceRollupKey.PROMPT, TraceFilter.none());

    assertEquals(2, rollups.size());
    var v1 =
        rollups.stream().filter(r -> r.key().get("promptVersion").equals("1")).findFirst().get();
    assertEquals("analytics", v1.key().get("promptName"));
    assertEquals(2, v1.runCount());
    assertEquals(20, v1.inputTokens());
    var v2 =
        rollups.stream().filter(r -> r.key().get("promptVersion").equals("2")).findFirst().get();
    assertEquals(1, v2.runCount());
    assertEquals(30, v2.inputTokens());
  }

  @Test
  void filterRestrictsByGroupAndTimeWindow() {
    seed("batch-a", "analytics", 1, 1000, null, Usage.of(10, 5), 100L);
    seedAt("batch-a", BASE.plusDays(7), 1000, Usage.of(90, 45), 900L);

    var windowed =
        store.summarize(
            TraceRollupKey.GROUP_ID,
            TraceFilter.newBuilder()
                .withGroupId("batch-a")
                .withSince(BASE.minusHours(1))
                .withUntil(BASE.plusDays(1))
                .build());

    assertEquals(1, windowed.size());
    assertEquals(1, windowed.getFirst().runCount());
    assertEquals(10, windowed.getFirst().inputTokens());
  }

  @Test
  void tracesWithoutUsageContributeZeroToTokenSums() {
    seedWithoutUsage("batch-a");

    var rollups = store.summarize(TraceRollupKey.GROUP_ID, TraceFilter.none());

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

  private void seed(
      String groupId,
      String promptName,
      int promptVersion,
      long durationMillis,
      String error,
      Usage usage,
      long costMicroUsd) {
    store.store(
        Trace.newBuilder()
            .withName("agent-run")
            .withStartTime(BASE)
            .withEndTime(BASE.plusNanos(durationMillis * 1_000_000L))
            .withError(error)
            .withGroupId(groupId)
            .withPromptName(promptName)
            .withPromptVersion(promptVersion)
            .withModelId("model-x")
            .withTotalTokens(usage.totalTokens())
            .withUsage(usage)
            .withCost(CostEstimate.ofMicroUsd(costMicroUsd))
            .build());
  }

  private void seedAt(
      String groupId, OffsetDateTime start, long durationMillis, Usage usage, long costMicroUsd) {
    store.store(
        Trace.newBuilder()
            .withName("agent-run")
            .withStartTime(start)
            .withEndTime(start.plusNanos(durationMillis * 1_000_000L))
            .withGroupId(groupId)
            .withTotalTokens(usage.totalTokens())
            .withUsage(usage)
            .withCost(CostEstimate.ofMicroUsd(costMicroUsd))
            .build());
  }

  private void seedWithoutGroup() {
    store.store(
        Trace.newBuilder()
            .withName("agent-run")
            .withStartTime(BASE)
            .withEndTime(BASE.plusSeconds(1))
            .build());
  }

  private void seedWithoutUsage(String groupId) {
    store.store(
        Trace.newBuilder()
            .withName("agent-run")
            .withStartTime(BASE)
            .withEndTime(BASE.plusSeconds(1))
            .withGroupId(groupId)
            .build());
  }

  private static Map<String, TraceRollup> index(java.util.List<TraceRollup> rollups, String dim) {
    return rollups.stream()
        .collect(java.util.stream.Collectors.toMap(r -> r.key().get(dim), r -> r));
  }
}
