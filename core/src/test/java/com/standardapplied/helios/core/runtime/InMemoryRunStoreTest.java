/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.Ids;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InMemoryRunStoreTest {

  private final InMemoryRunStore store = new InMemoryRunStore();

  private static AgentRun runAt(UUID id, AgentRunStatus status, OffsetDateTime checkpoint) {
    return AgentRun.newBuilder()
        .withRunId(id)
        .withStatus(status)
        .withStartedAt(checkpoint)
        .withLastCheckpointAt(checkpoint)
        .build();
  }

  @Test
  void checkpointThenFind() {
    var id = Ids.newId();
    var run = runAt(id, AgentRunStatus.RUNNING, Ids.now());
    store.checkpoint(run);
    assertEquals(run, store.find(id).orElseThrow());
  }

  @Test
  void checkpointUpdatesInPlace() {
    var id = Ids.newId();
    store.checkpoint(runAt(id, AgentRunStatus.RUNNING, Ids.now()));
    store.checkpoint(runAt(id, AgentRunStatus.COMPLETED, Ids.now()));
    assertEquals(AgentRunStatus.COMPLETED, store.find(id).orElseThrow().status());
  }

  @Test
  void findUnknownReturnsEmpty() {
    assertTrue(store.find(Ids.newId()).isEmpty());
  }

  @Test
  void findNullReturnsEmpty() {
    assertTrue(store.find(null).isEmpty());
  }

  @Test
  void findByStatusReturnsOnlyMatching() {
    store.checkpoint(runAt(Ids.newId(), AgentRunStatus.RUNNING, Ids.now()));
    store.checkpoint(runAt(Ids.newId(), AgentRunStatus.COMPLETED, Ids.now()));
    store.checkpoint(runAt(Ids.newId(), AgentRunStatus.RUNNING, Ids.now()));
    assertEquals(2, store.findByStatus(AgentRunStatus.RUNNING).size());
    assertEquals(1, store.findByStatus(AgentRunStatus.COMPLETED).size());
    assertEquals(0, store.findByStatus(AgentRunStatus.FAILED).size());
  }

  @Test
  void findByStatusOrdersNewestFirst() {
    var older = OffsetDateTime.now().minusMinutes(5);
    var newer = OffsetDateTime.now();
    var olderId = Ids.newId();
    var newerId = Ids.newId();
    store.checkpoint(runAt(olderId, AgentRunStatus.RUNNING, older));
    store.checkpoint(runAt(newerId, AgentRunStatus.RUNNING, newer));
    var listed = store.findByStatus(AgentRunStatus.RUNNING);
    assertEquals(newerId, listed.get(0).runId());
    assertEquals(olderId, listed.get(1).runId());
  }

  @Test
  void rejectsNullCheckpoint() {
    assertThrows(NullPointerException.class, () -> store.checkpoint(null));
  }

  @Test
  void rejectsNullStatus() {
    assertThrows(NullPointerException.class, () -> store.findByStatus(null));
  }

  @Test
  void purgeRemovesOldTerminalRunsOnly() {
    var oldCompleted = Ids.newId();
    var oldFailed = Ids.newId();
    var recentCompleted = Ids.newId();
    var oldRunning = Ids.newId(); // not terminal — should not be purged

    var anHourAgo = OffsetDateTime.now().minusHours(1);
    var twoMinutesAgo = OffsetDateTime.now().minusMinutes(2);

    store.checkpoint(
        AgentRun.newBuilder()
            .withRunId(oldCompleted)
            .withStatus(AgentRunStatus.COMPLETED)
            .withStartedAt(anHourAgo)
            .withLastCheckpointAt(anHourAgo)
            .withEndedAt(anHourAgo)
            .build());
    store.checkpoint(
        AgentRun.newBuilder()
            .withRunId(oldFailed)
            .withStatus(AgentRunStatus.FAILED)
            .withStartedAt(anHourAgo)
            .withLastCheckpointAt(anHourAgo)
            .withEndedAt(anHourAgo)
            .build());
    store.checkpoint(
        AgentRun.newBuilder()
            .withRunId(recentCompleted)
            .withStatus(AgentRunStatus.COMPLETED)
            .withStartedAt(twoMinutesAgo)
            .withLastCheckpointAt(twoMinutesAgo)
            .withEndedAt(twoMinutesAgo)
            .build());
    store.checkpoint(
        AgentRun.newBuilder()
            .withRunId(oldRunning)
            .withStatus(AgentRunStatus.RUNNING)
            .withStartedAt(anHourAgo)
            .withLastCheckpointAt(anHourAgo)
            .build());

    var deleted = store.purgeOlderThan(Duration.ofMinutes(10));

    assertEquals(2, deleted);
    assertTrue(store.find(oldCompleted).isEmpty());
    assertTrue(store.find(oldFailed).isEmpty());
    assertTrue(store.find(recentCompleted).isPresent(), "recent run must survive");
    assertTrue(store.find(oldRunning).isPresent(), "non-terminal run must survive");
  }

  @Test
  void purgeIgnoresTerminalRunsWithNullEndedAt() {
    var weirdRun = Ids.newId();
    store.checkpoint(
        AgentRun.newBuilder()
            .withRunId(weirdRun)
            .withStatus(AgentRunStatus.COMPLETED)
            .withStartedAt(OffsetDateTime.now().minusDays(7))
            .withLastCheckpointAt(OffsetDateTime.now().minusDays(7))
            // endedAt left null — defensive against malformed rows
            .build());
    var deleted = store.purgeOlderThan(Duration.ofMinutes(1));
    assertEquals(0, deleted);
    assertTrue(store.find(weirdRun).isPresent());
  }

  @Test
  void purgeRejectsNullDuration() {
    assertThrows(NullPointerException.class, () -> store.purgeOlderThan(null));
  }

  @Test
  void purgeRejectsNegativeDuration() {
    assertThrows(
        IllegalArgumentException.class, () -> store.purgeOlderThan(Duration.ofMinutes(-1)));
  }

  @Test
  void purgeWithZeroDurationDeletesAllTerminalRuns() {
    var id = Ids.newId();
    store.checkpoint(
        AgentRun.newBuilder()
            .withRunId(id)
            .withStatus(AgentRunStatus.COMPLETED)
            .withStartedAt(OffsetDateTime.now())
            .withLastCheckpointAt(OffsetDateTime.now())
            .withEndedAt(OffsetDateTime.now().minusSeconds(1))
            .build());
    var deleted = store.purgeOlderThan(Duration.ZERO);
    assertEquals(1, deleted);
  }
}
