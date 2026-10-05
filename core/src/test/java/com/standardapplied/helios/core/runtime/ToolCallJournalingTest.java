/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ToolCallJournalingTest {

  @Test
  void constructorRejectsNullDurability() {
    assertThrows(NullPointerException.class, () -> new ToolCallJournaling(null));
  }

  @Test
  void startWritesAStartedEntryAndReportsIt() {
    var durability = Durability.inMemory();
    var journaling = new ToolCallJournaling(durability);
    var runId = UUID.randomUUID();

    var wrote = journaling.start(runId, 2, "tcid", "tool", Map.of("path", "a.txt"));

    assertTrue(wrote);
    var entry = durability.toolCallJournal().all(runId).getFirst();
    assertEquals(2, entry.iteration());
    assertEquals("tcid", entry.toolCallId());
    assertEquals("tool", entry.toolName());
    assertEquals(Map.of("path", "a.txt"), entry.args());
    assertEquals(ToolCallStatus.STARTED, entry.status());
  }

  @Test
  void startWithNullRunIdReturnsFalse() {
    var journaling = new ToolCallJournaling(Durability.inMemory());

    var wrote = journaling.start(null, 0, "tcid", "tool", Map.of());

    assertFalse(wrote);
  }

  @Test
  void startSurvivesRuntimeException() {
    var journaling = new ToolCallJournaling(throwingJournal());

    assertFalse(journaling.start(UUID.randomUUID(), 0, "tcid", "tool", Map.of()));
  }

  @Test
  void completeRoutesASuccessfulResultToComplete() {
    var journal = new RecordingToolCallJournal();
    var journaling = new ToolCallJournaling(durabilityWith(journal));

    journaling.complete(UUID.randomUUID(), "tcid", ToolResult.success("ok"));

    assertEquals(1, journal.completeCount());
    assertEquals(0, journal.failCount());
  }

  @Test
  void completeRoutesAFailedResultToFail() {
    var journal = new RecordingToolCallJournal();
    var journaling = new ToolCallJournaling(durabilityWith(journal));

    journaling.complete(UUID.randomUUID(), "tcid", ToolResult.failure("boom"));

    assertEquals(0, journal.completeCount());
    assertEquals(1, journal.failCount());
  }

  @Test
  void completeSwallowsRuntimeException() {
    var journaling = new ToolCallJournaling(throwingJournal());

    // Must not throw — the tool already executed and the caller is owed its result.
    journaling.complete(UUID.randomUUID(), "tcid", ToolResult.success("ok"));
  }

  @Test
  void failWritesAFailEntry() {
    var journal = new RecordingToolCallJournal();
    var journaling = new ToolCallJournaling(durabilityWith(journal));

    journaling.fail(UUID.randomUUID(), "tcid", "exception path");

    assertEquals(1, journal.failCount());
  }

  @Test
  void failSwallowsRuntimeException() {
    var journaling = new ToolCallJournaling(throwingJournal());

    // Must not throw — original exception path needs to keep propagating outward.
    journaling.fail(UUID.randomUUID(), "tcid", "boom");
  }

  @Test
  void inflightListsTheStartedEntries() {
    var journaling = new ToolCallJournaling(Durability.inMemory());
    var runId = UUID.randomUUID();
    journaling.start(runId, 0, "open", "tool", Map.of());
    journaling.start(runId, 0, "done", "tool", Map.of());
    journaling.complete(runId, "done", ToolResult.success("ok"));

    var inflight = journaling.inflight(runId);

    assertEquals(List.of("open"), inflight.stream().map(ToolCallRecord::toolCallId).toList());
  }

  @Test
  void markInflightFailedFailsTheEntryWithTheReason() {
    var durability = Durability.inMemory();
    var journaling = new ToolCallJournaling(durability);
    var runId = UUID.randomUUID();
    journaling.start(runId, 0, "tcid", "tool", Map.of());

    journaling.markInflightFailed(runId, "tcid", "synthetic reason");

    var entry = durability.toolCallJournal().all(runId).getFirst();
    assertEquals(ToolCallStatus.FAILED, entry.status());
    assertEquals("synthetic reason", entry.error());
  }

  @Test
  void markInflightFailedSwallowsRuntimeException() {
    var journaling = new ToolCallJournaling(throwingJournal());

    journaling.markInflightFailed(UUID.randomUUID(), "tcid", "synthetic reason");
  }

  private static Durability durabilityWith(ToolCallJournal journal) {
    return Durability.newBuilder()
        .withRunStore(new InMemoryRunStore())
        .withToolCallJournal(journal)
        .build();
  }

  private static Durability throwingJournal() {
    return durabilityWith(new ThrowingToolCallJournal());
  }

  private static final class RecordingToolCallJournal implements ToolCallJournal {
    private final AtomicInteger completes = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();
    private final InMemoryToolCallJournal delegate = new InMemoryToolCallJournal();

    int completeCount() {
      return completes.get();
    }

    int failCount() {
      return failures.get();
    }

    @Override
    public void start(ToolCallRecord record) {
      delegate.start(record);
    }

    @Override
    public void complete(UUID runId, String toolCallId, String output) {
      completes.incrementAndGet();
      delegate.complete(runId, toolCallId, output);
    }

    @Override
    public void fail(UUID runId, String toolCallId, String error) {
      failures.incrementAndGet();
      delegate.fail(runId, toolCallId, error);
    }

    @Override
    public List<ToolCallRecord> inflight(UUID runId) {
      return delegate.inflight(runId);
    }

    @Override
    public List<ToolCallRecord> all(UUID runId) {
      return delegate.all(runId);
    }
  }

  private static final class ThrowingToolCallJournal implements ToolCallJournal {
    @Override
    public void start(ToolCallRecord record) {
      throw new RuntimeException("boom-start");
    }

    @Override
    public void complete(UUID runId, String toolCallId, String output) {
      throw new RuntimeException("boom-complete");
    }

    @Override
    public void fail(UUID runId, String toolCallId, String error) {
      throw new RuntimeException("boom-fail");
    }

    @Override
    public List<ToolCallRecord> inflight(UUID runId) {
      return List.of();
    }

    @Override
    public List<ToolCallRecord> all(UUID runId) {
      return List.of();
    }
  }
}
