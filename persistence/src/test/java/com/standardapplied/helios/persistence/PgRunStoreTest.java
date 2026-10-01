/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.Ids;
import ai.singlr.core.runtime.AgentRun;
import ai.singlr.core.runtime.AgentRunStatus;
import ai.singlr.core.runtime.ToolCallRecord;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PgRunStoreTest {

  private PgRunStore store;

  @BeforeEach
  void setUp() {
    PgTestSupport.truncateRuntime();
    store = new PgRunStore(PgTestSupport.pgConfig());
  }

  private static AgentRun newRun(UUID id, AgentRunStatus status) {
    return AgentRun.newBuilder()
        .withRunId(id)
        .withSessionId(Ids.newId())
        .withAgentId("research-bot")
        .withUserId("u-1")
        .withStatus(status)
        .withIteration(0)
        .withStartedAt(Ids.now())
        .withLastCheckpointAt(Ids.now())
        .build();
  }

  @Test
  void checkpointInsertsThenUpdates() {
    var id = Ids.newId();
    store.checkpoint(newRun(id, AgentRunStatus.RUNNING));
    var first = store.find(id).orElseThrow();
    assertEquals(AgentRunStatus.RUNNING, first.status());

    store.checkpoint(
        AgentRun.newBuilder(first)
            .withStatus(AgentRunStatus.COMPLETED)
            .withIteration(5)
            .withEndedAt(Ids.now())
            .build());
    var second = store.find(id).orElseThrow();
    assertEquals(AgentRunStatus.COMPLETED, second.status());
    assertEquals(5, second.iteration());
    assertNotNull(second.endedAt());
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
  void findByStatusFiltersAndOrdersDescending() throws Exception {
    var older = Ids.newId();
    var newer = Ids.newId();
    store.checkpoint(
        AgentRun.newBuilder(newRun(older, AgentRunStatus.RUNNING))
            .withLastCheckpointAt(OffsetDateTime.now().minusMinutes(10))
            .build());
    Thread.sleep(5);
    store.checkpoint(
        AgentRun.newBuilder(newRun(newer, AgentRunStatus.RUNNING))
            .withLastCheckpointAt(OffsetDateTime.now())
            .build());
    store.checkpoint(newRun(Ids.newId(), AgentRunStatus.COMPLETED));

    var listed = store.findByStatus(AgentRunStatus.RUNNING);
    assertEquals(2, listed.size());
    assertEquals(newer, listed.get(0).runId());
    assertEquals(older, listed.get(1).runId());
  }

  @Test
  void findByStatusEmptyReturnsEmpty() {
    assertTrue(store.findByStatus(AgentRunStatus.FAILED).isEmpty());
  }

  @Test
  void rejectsNullCheckpoint() {
    assertThrows(NullPointerException.class, () -> store.checkpoint(null));
  }

  @Test
  void rejectsNullStatusFilter() {
    assertThrows(NullPointerException.class, () -> store.findByStatus(null));
  }

  @Test
  void purgeRemovesTerminalRunsOlderThanCutoff() {
    var oldCompleted = Ids.newId();
    var recentCompleted = Ids.newId();
    var oldRunning = Ids.newId();

    var anHourAgo = OffsetDateTime.now().minusHours(1);
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
            .withRunId(recentCompleted)
            .withStatus(AgentRunStatus.COMPLETED)
            .withStartedAt(OffsetDateTime.now().minusSeconds(30))
            .withLastCheckpointAt(OffsetDateTime.now().minusSeconds(30))
            .withEndedAt(OffsetDateTime.now().minusSeconds(30))
            .build());
    store.checkpoint(
        AgentRun.newBuilder()
            .withRunId(oldRunning)
            .withStatus(AgentRunStatus.RUNNING)
            .withStartedAt(anHourAgo)
            .withLastCheckpointAt(anHourAgo)
            .build());

    var deleted = store.purgeOlderThan(Duration.ofMinutes(10));

    assertEquals(1, deleted);
    assertTrue(store.find(oldCompleted).isEmpty());
    assertTrue(store.find(recentCompleted).isPresent());
    assertTrue(store.find(oldRunning).isPresent());
  }

  @Test
  void purgeCascadesToToolCallEntries() {
    var journal = new PgToolCallJournal(PgTestSupport.pgConfig());
    var oldRun = Ids.newId();
    var anHourAgo = OffsetDateTime.now().minusHours(1);
    store.checkpoint(
        AgentRun.newBuilder()
            .withRunId(oldRun)
            .withStatus(AgentRunStatus.COMPLETED)
            .withStartedAt(anHourAgo)
            .withLastCheckpointAt(anHourAgo)
            .withEndedAt(anHourAgo)
            .build());
    journal.start(
        ToolCallRecord.newBuilder()
            .withRunId(oldRun)
            .withToolCallId("call_1")
            .withToolName("send")
            .withStartedAt(anHourAgo)
            .build());
    journal.complete(oldRun, "call_1", "ok");
    assertTrue(journal.all(oldRun).size() == 1, "journal seeded");

    store.purgeOlderThan(Duration.ofMinutes(10));

    assertTrue(journal.all(oldRun).isEmpty(), "journal entries cascade-deleted");
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
  void preservesNullSessionAndUser() {
    var id = Ids.newId();
    var run =
        AgentRun.newBuilder()
            .withRunId(id)
            .withAgentId("a")
            .withStatus(AgentRunStatus.RUNNING)
            .withStartedAt(Ids.now())
            .withLastCheckpointAt(Ids.now())
            .build();
    store.checkpoint(run);
    var loaded = store.find(id).orElseThrow();
    assertNull(loaded.sessionId());
    assertNull(loaded.userId());
  }
}
