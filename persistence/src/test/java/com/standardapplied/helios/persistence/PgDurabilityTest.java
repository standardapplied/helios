/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.Ids;
import ai.singlr.core.runtime.AgentRun;
import ai.singlr.core.runtime.AgentRunStatus;
import ai.singlr.core.runtime.UnsafeResumePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PgDurabilityTest {

  @BeforeEach
  void setUp() {
    PgTestSupport.truncateRuntime();
  }

  @Test
  void factoryReturnsConfiguredBundle() {
    var d = PgDurability.of(PgTestSupport.pgConfig());
    assertNotNull(d.runStore());
    assertNotNull(d.toolCallJournal());
    assertTrue(d.runStore() instanceof PgRunStore);
    assertTrue(d.toolCallJournal() instanceof PgToolCallJournal);
    assertEquals(UnsafeResumePolicy.FAIL_LOUD, d.unsafeResumePolicy());
    assertTrue(d.idempotentToolsOverride().isEmpty());
  }

  @Test
  void factoryProducesIndependentInstancesEachCall() {
    var first = PgDurability.of(PgTestSupport.pgConfig());
    var second = PgDurability.of(PgTestSupport.pgConfig());
    assertNotSame(first.runStore(), second.runStore());
    assertNotSame(first.toolCallJournal(), second.toolCallJournal());
  }

  @Test
  void factoryRejectsNullConfig() {
    assertThrows(NullPointerException.class, () -> PgDurability.of(null));
  }

  @Test
  void bundleEndToEndCheckpointAndJournal() {
    var d = PgDurability.of(PgTestSupport.pgConfig());
    var runId = Ids.newId();
    d.runStore()
        .checkpoint(
            AgentRun.newBuilder()
                .withRunId(runId)
                .withSessionId(Ids.newId())
                .withAgentId("test")
                .withStatus(AgentRunStatus.RUNNING)
                .withStartedAt(Ids.now())
                .withLastCheckpointAt(Ids.now())
                .build());
    var loaded = d.runStore().find(runId).orElseThrow();
    assertEquals(AgentRunStatus.RUNNING, loaded.status());
  }
}
