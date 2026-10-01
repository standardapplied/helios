/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.Ids;
import com.standardapplied.helios.core.runtime.AgentRun;
import com.standardapplied.helios.core.runtime.AgentRunStatus;
import com.standardapplied.helios.core.runtime.UnsafeResumePolicy;
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
