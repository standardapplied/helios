/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.core.common.Ids;
import org.junit.jupiter.api.Test;

class AgentRunTest {

  @Test
  void builderHappyPath() {
    var runId = Ids.newId();
    var sessionId = Ids.newId();
    var now = Ids.now();

    var run =
        AgentRun.newBuilder()
            .withRunId(runId)
            .withSessionId(sessionId)
            .withAgentId("research-bot")
            .withUserId("u-1")
            .withStatus(AgentRunStatus.RUNNING)
            .withIteration(3)
            .withStartedAt(now)
            .withLastCheckpointAt(now)
            .build();

    assertEquals(runId, run.runId());
    assertEquals(sessionId, run.sessionId());
    assertEquals("research-bot", run.agentId());
    assertEquals("u-1", run.userId());
    assertEquals(AgentRunStatus.RUNNING, run.status());
    assertEquals(3, run.iteration());
    assertEquals(now, run.startedAt());
    assertEquals(now, run.lastCheckpointAt());
    assertNull(run.endedAt());
    assertNull(run.error());
  }

  @Test
  void builderDefaultsStatusToRunning() {
    var run =
        AgentRun.newBuilder()
            .withRunId(Ids.newId())
            .withStartedAt(Ids.now())
            .withLastCheckpointAt(Ids.now())
            .build();
    assertEquals(AgentRunStatus.RUNNING, run.status());
    assertEquals(0, run.iteration());
  }

  @Test
  void builderRoundtripPreservesAllFields() {
    var original =
        AgentRun.newBuilder()
            .withRunId(Ids.newId())
            .withSessionId(Ids.newId())
            .withAgentId("a")
            .withUserId("u")
            .withStatus(AgentRunStatus.COMPLETED)
            .withIteration(7)
            .withStartedAt(Ids.now())
            .withLastCheckpointAt(Ids.now())
            .withEndedAt(Ids.now())
            .withError(null)
            .build();
    var copy = AgentRun.newBuilder(original).build();
    assertEquals(original, copy);
  }

  @Test
  void failedRunCarriesError() {
    var run =
        AgentRun.newBuilder()
            .withRunId(Ids.newId())
            .withStatus(AgentRunStatus.FAILED)
            .withStartedAt(Ids.now())
            .withLastCheckpointAt(Ids.now())
            .withEndedAt(Ids.now())
            .withError("model timeout")
            .build();
    assertEquals(AgentRunStatus.FAILED, run.status());
    assertEquals("model timeout", run.error());
  }

  @Test
  void rejectsNullRunId() {
    assertThrows(
        NullPointerException.class,
        () ->
            AgentRun.newBuilder().withStartedAt(Ids.now()).withLastCheckpointAt(Ids.now()).build());
  }

  @Test
  void rejectsNullStatus() {
    assertThrows(
        NullPointerException.class,
        () ->
            AgentRun.newBuilder()
                .withRunId(Ids.newId())
                .withStatus(null)
                .withStartedAt(Ids.now())
                .withLastCheckpointAt(Ids.now())
                .build());
  }

  @Test
  void rejectsNullStartedAt() {
    assertThrows(
        NullPointerException.class,
        () -> AgentRun.newBuilder().withRunId(Ids.newId()).withLastCheckpointAt(Ids.now()).build());
  }

  @Test
  void rejectsNullLastCheckpointAt() {
    assertThrows(
        NullPointerException.class,
        () -> AgentRun.newBuilder().withRunId(Ids.newId()).withStartedAt(Ids.now()).build());
  }

  @Test
  void rejectsNegativeIteration() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AgentRun.newBuilder()
                .withRunId(Ids.newId())
                .withIteration(-1)
                .withStartedAt(Ids.now())
                .withLastCheckpointAt(Ids.now())
                .build());
  }

  @Test
  void enumValues() {
    assertSame(AgentRunStatus.RUNNING, AgentRunStatus.valueOf("RUNNING"));
    assertSame(AgentRunStatus.SUSPENDED, AgentRunStatus.valueOf("SUSPENDED"));
    assertSame(AgentRunStatus.COMPLETED, AgentRunStatus.valueOf("COMPLETED"));
    assertSame(AgentRunStatus.FAILED, AgentRunStatus.valueOf("FAILED"));
    assertEquals(4, AgentRunStatus.values().length);
  }
}
