/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.Ids;
import ai.singlr.core.common.Result;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DurableResumeScannerTest {

  private static AgentRun staleRunning(String agentId) {
    var id = Ids.newId();
    return AgentRun.newBuilder()
        .withRunId(id)
        .withAgentId(agentId)
        .withStatus(AgentRunStatus.RUNNING)
        .withStartedAt(OffsetDateTime.now().minusHours(1))
        .withLastCheckpointAt(OffsetDateTime.now().minusHours(1))
        .build();
  }

  private static AgentRun freshRunning(String agentId) {
    var id = Ids.newId();
    return AgentRun.newBuilder()
        .withRunId(id)
        .withAgentId(agentId)
        .withStatus(AgentRunStatus.RUNNING)
        .withStartedAt(OffsetDateTime.now())
        .withLastCheckpointAt(OffsetDateTime.now())
        .build();
  }

  @Test
  void scansAndResumesStaleRuns() {
    var d = Durability.inMemory();
    var stale1 = staleRunning("research-bot");
    var stale2 = staleRunning("research-bot");
    d.runStore().checkpoint(stale1);
    d.runStore().checkpoint(stale2);

    var resumed = ConcurrentHashMap.<UUID>newKeySet();
    var scanner =
        DurableResumeScanner.builder(d)
            .register(
                "research-bot",
                runId -> {
                  resumed.add(runId);
                  return Result.success("ok");
                })
            .withStaleAfter(Duration.ofMinutes(5))
            .build();

    var result = scanner.scan();

    assertEquals(2, result.scanned());
    assertEquals(2, result.resumed());
    assertEquals(2, result.recovered());
    assertEquals(0, result.failed());
    assertEquals(0, result.skippedFresh());
    assertEquals(0, result.skippedUnknownAgent());
    assertTrue(resumed.contains(stale1.runId()));
    assertTrue(resumed.contains(stale2.runId()));
  }

  @Test
  void skipsFreshRuns() {
    var d = Durability.inMemory();
    d.runStore().checkpoint(freshRunning("research-bot"));
    var scanner =
        DurableResumeScanner.builder(d)
            .register("research-bot", runId -> Result.success("ok"))
            .withStaleAfter(Duration.ofMinutes(5))
            .build();
    var result = scanner.scan();
    assertEquals(1, result.scanned());
    assertEquals(1, result.skippedFresh());
    assertEquals(0, result.resumed());
  }

  @Test
  void skipsUnknownAgentIds() {
    var d = Durability.inMemory();
    d.runStore().checkpoint(staleRunning("unknown-bot"));
    var scanner =
        DurableResumeScanner.builder(d)
            .register("research-bot", runId -> Result.success("ok"))
            .build();
    var result = scanner.scan();
    assertEquals(1, result.scanned());
    assertEquals(1, result.skippedUnknownAgent());
    assertEquals(0, result.resumed());
  }

  @Test
  void countsFailuresFromResolver() {
    var d = Durability.inMemory();
    d.runStore().checkpoint(staleRunning("research-bot"));
    var scanner =
        DurableResumeScanner.builder(d)
            .register("research-bot", runId -> Result.failure("nope"))
            .build();
    var result = scanner.scan();
    assertEquals(1, result.resumed());
    assertEquals(0, result.recovered());
    assertEquals(1, result.failed());
  }

  @Test
  void countsThrownResolverAsFailure() {
    var d = Durability.inMemory();
    d.runStore().checkpoint(staleRunning("research-bot"));
    var scanner =
        DurableResumeScanner.builder(d)
            .register(
                "research-bot",
                runId -> {
                  throw new RuntimeException("boom");
                })
            .build();
    var result = scanner.scan();
    assertEquals(1, result.resumed());
    assertEquals(1, result.failed());
  }

  @Test
  void inspectsRunningAndSuspendedRuns() {
    var d = Durability.inMemory();
    d.runStore().checkpoint(staleRunning("research-bot"));
    d.runStore()
        .checkpoint(
            AgentRun.newBuilder()
                .withRunId(Ids.newId())
                .withAgentId("research-bot")
                .withStatus(AgentRunStatus.SUSPENDED)
                .withStartedAt(OffsetDateTime.now().minusHours(1))
                .withLastCheckpointAt(OffsetDateTime.now().minusHours(1))
                .build());
    // Terminal runs must not be picked up
    d.runStore()
        .checkpoint(
            AgentRun.newBuilder()
                .withRunId(Ids.newId())
                .withAgentId("research-bot")
                .withStatus(AgentRunStatus.COMPLETED)
                .withStartedAt(OffsetDateTime.now().minusHours(1))
                .withLastCheckpointAt(OffsetDateTime.now().minusHours(1))
                .withEndedAt(OffsetDateTime.now().minusHours(1))
                .build());

    var resumed = new AtomicInteger();
    var scanner =
        DurableResumeScanner.builder(d)
            .register(
                "research-bot",
                runId -> {
                  resumed.incrementAndGet();
                  return Result.success("ok");
                })
            .build();
    var result = scanner.scan();
    assertEquals(2, result.scanned(), "RUNNING + SUSPENDED scanned, COMPLETED ignored");
    assertEquals(2, resumed.get());
  }

  @Test
  void boundedConcurrencyByMaxConcurrent() throws Exception {
    var d = Durability.inMemory();
    for (int i = 0; i < 10; i++) {
      d.runStore().checkpoint(staleRunning("research-bot"));
    }
    var concurrent = new AtomicInteger();
    var maxObserved = new AtomicInteger();
    var scanner =
        DurableResumeScanner.builder(d)
            .withMaxConcurrent(3)
            .register(
                "research-bot",
                runId -> {
                  var c = concurrent.incrementAndGet();
                  maxObserved.updateAndGet(prev -> Math.max(prev, c));
                  try {
                    Thread.sleep(20);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  concurrent.decrementAndGet();
                  return Result.success("ok");
                })
            .build();
    scanner.scan();
    assertTrue(
        maxObserved.get() <= 3, "maxConcurrent=3 must hold (observed " + maxObserved.get() + ")");
  }

  @Test
  void registerAgentConvenience() {
    var d = Durability.inMemory();
    var run = staleRunning("research-bot");
    d.runStore().checkpoint(run);
    var calledFor = new ConcurrentHashMap<UUID, Boolean>();
    DurableResumeScanner.AgentResumable agent =
        runId -> {
          calledFor.put(runId, true);
          return Result.success("ok");
        };
    var scanner = DurableResumeScanner.builder(d).registerAgent("research-bot", agent).build();
    scanner.scan();
    assertTrue(calledFor.containsKey(run.runId()));
  }

  @Test
  void registerWorkflowConvenienceUsesPrefixedAgentId() {
    var d = Durability.inMemory();
    var run = staleRunning("workflow.ingest");
    d.runStore().checkpoint(run);
    var calledFor = new ConcurrentHashMap<UUID, Boolean>();
    DurableResumeScanner.WorkflowResumable workflow =
        runId -> {
          calledFor.put(runId, true);
          return Result.success("ok");
        };
    var scanner = DurableResumeScanner.builder(d).registerWorkflow("ingest", workflow).build();
    scanner.scan();
    assertTrue(calledFor.containsKey(run.runId()));
  }

  @Test
  void builderRequiresAtLeastOneRegistration() {
    var d = Durability.inMemory();
    var b = DurableResumeScanner.builder(d);
    assertThrows(IllegalStateException.class, b::build);
  }

  @Test
  void builderRejectsBlankAgentId() {
    var d = Durability.inMemory();
    var b = DurableResumeScanner.builder(d);
    assertThrows(IllegalArgumentException.class, () -> b.register("", id -> Result.success("ok")));
    assertThrows(
        IllegalArgumentException.class, () -> b.register(null, id -> Result.success("ok")));
  }

  @Test
  void builderRejectsNegativeStaleAfter() {
    var d = Durability.inMemory();
    var b = DurableResumeScanner.builder(d);
    assertThrows(IllegalArgumentException.class, () -> b.withStaleAfter(Duration.ofMinutes(-1)));
  }

  @Test
  void builderRejectsNullStaleAfter() {
    var d = Durability.inMemory();
    var b = DurableResumeScanner.builder(d);
    assertThrows(NullPointerException.class, () -> b.withStaleAfter(null));
  }

  @Test
  void builderRejectsZeroOrNegativeMaxConcurrent() {
    var d = Durability.inMemory();
    var b = DurableResumeScanner.builder(d);
    assertThrows(IllegalArgumentException.class, () -> b.withMaxConcurrent(0));
    assertThrows(IllegalArgumentException.class, () -> b.withMaxConcurrent(-1));
  }

  @Test
  void builderRejectsNullDurability() {
    assertThrows(NullPointerException.class, () -> DurableResumeScanner.builder(null));
  }

  @Test
  void builderRejectsNullResolver() {
    var d = Durability.inMemory();
    var b = DurableResumeScanner.builder(d);
    assertThrows(NullPointerException.class, () -> b.register("a", null));
    assertThrows(NullPointerException.class, () -> b.registerAgent("a", null));
    assertThrows(NullPointerException.class, () -> b.registerWorkflow("a", null));
  }
}
