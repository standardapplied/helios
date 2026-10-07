/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.Ids;
import com.standardapplied.helios.core.runtime.ToolCallJournal;
import com.standardapplied.helios.core.runtime.ToolCallRecord;
import com.standardapplied.helios.core.runtime.ToolCallStatus;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The behaviour every {@link ToolCallJournal} shares: the started-to-terminal transition, in-flight
 * and full listings, no-op transitions and null rejection. An implementation's test extends this
 * class, supplies an empty journal through {@link #createJournal()} and adds only what is specific
 * to it.
 */
public abstract class ToolCallJournalContract {

  /** The journal under test, created empty before each test by {@link #createJournal()}. */
  protected ToolCallJournal journal;

  /**
   * Creates the journal under test, holding no entries.
   *
   * @return an empty journal
   */
  protected abstract ToolCallJournal createJournal();

  /**
   * Returns a fresh run id the journal accepts entries for. Override when the journal requires the
   * run to exist before an entry references it.
   *
   * @return a run id no entry references yet
   */
  protected UUID newRunId() {
    return Ids.newId();
  }

  /**
   * A {@link ToolCallStatus#STARTED} record at iteration 0 with the arguments {@code {k=v}}.
   *
   * @param runId the run the call belongs to
   * @param callId the tool-call id
   * @param toolName the tool's name
   * @return the record, started now
   */
  protected static ToolCallRecord started(UUID runId, String callId, String toolName) {
    return ToolCallRecord.newBuilder()
        .withRunId(runId)
        .withIteration(0)
        .withToolCallId(callId)
        .withToolName(toolName)
        .withArgs(Map.of("k", "v"))
        .withStartedAt(Ids.now())
        .build();
  }

  @BeforeEach
  void setUpJournal() {
    journal = createJournal();
  }

  @Test
  void startThenComplete() {
    var runId = newRunId();
    journal.start(started(runId, "c1", "weather"));
    journal.complete(runId, "c1", "sunny");

    var all = journal.all(runId);
    assertEquals(1, all.size());
    assertEquals(ToolCallStatus.SUCCEEDED, all.get(0).status());
    assertEquals("sunny", all.get(0).output());
    assertNotNull(all.get(0).endedAt());
    assertNull(all.get(0).error());
    assertEquals(Map.of("k", "v"), all.get(0).args());
  }

  @Test
  void startThenFail() {
    var runId = newRunId();
    journal.start(started(runId, "c1", "weather"));
    journal.fail(runId, "c1", "timeout");

    var rec = journal.all(runId).get(0);
    assertEquals(ToolCallStatus.FAILED, rec.status());
    assertEquals("timeout", rec.error());
    assertNull(rec.output());
  }

  @Test
  void inflightExcludesTerminal() {
    var runId = newRunId();
    journal.start(started(runId, "c1", "send"));
    journal.start(started(runId, "c2", "send"));
    journal.start(started(runId, "c3", "send"));
    journal.complete(runId, "c1", "ok");
    journal.fail(runId, "c2", "boom");

    var inflight = journal.inflight(runId);
    assertEquals(1, inflight.size());
    assertEquals("c3", inflight.get(0).toolCallId());
  }

  @Test
  void inflightOnUnknownRunIsEmpty() {
    assertTrue(journal.inflight(Ids.newId()).isEmpty());
  }

  @Test
  void allOnUnknownRunIsEmpty() {
    assertTrue(journal.all(Ids.newId()).isEmpty());
  }

  @Test
  void allOrdersByStartTime() {
    var runId = newRunId();
    journal.start(started(runId, "c1", "weather"));
    journal.start(started(runId, "c2", "weather"));
    var all = journal.all(runId);
    assertEquals("c1", all.get(0).toolCallId());
    assertEquals("c2", all.get(1).toolCallId());
  }

  @Test
  void completeOnUnknownRunIsNoOp() {
    var runId = Ids.newId();
    journal.complete(runId, "c1", "ok");
    assertTrue(journal.all(runId).isEmpty());
  }

  @Test
  void completeNoMatchIsNoOp() {
    var runId = newRunId();
    journal.complete(runId, "missing", "irrelevant");
    assertTrue(journal.all(runId).isEmpty());
  }

  @Test
  void completeOnUnknownCallIdIsNoOp() {
    var runId = newRunId();
    journal.start(started(runId, "c1", "weather"));
    journal.complete(runId, "missing", "ok");
    var rec = journal.all(runId).get(0);
    assertEquals(ToolCallStatus.STARTED, rec.status());
  }

  @Test
  void completeAlreadyTerminalIsNoOp() {
    var runId = newRunId();
    journal.start(started(runId, "c1", "weather"));
    journal.complete(runId, "c1", "ok");
    journal.complete(runId, "c1", "ok again");
    var rec = journal.all(runId).get(0);
    assertEquals(ToolCallStatus.SUCCEEDED, rec.status());
    assertEquals("ok", rec.output());
  }

  @Test
  void completeAfterTerminalIsNoOp() {
    var runId = newRunId();
    journal.start(started(runId, "c1", "send"));
    journal.fail(runId, "c1", "first");
    journal.complete(runId, "c1", "second");
    var rec = journal.all(runId).get(0);
    assertEquals(ToolCallStatus.FAILED, rec.status());
    assertEquals("first", rec.error());
  }

  @Test
  void failOnUnknownRunIsNoOp() {
    var runId = Ids.newId();
    journal.fail(runId, "c1", "boom");
    assertTrue(journal.all(runId).isEmpty());
  }

  @Test
  void argsNullPersistsAsNull() {
    var runId = newRunId();
    var record =
        ToolCallRecord.newBuilder()
            .withRunId(runId)
            .withIteration(0)
            .withToolCallId("c1")
            .withToolName("send")
            .withArgs(null)
            .withStartedAt(Ids.now())
            .build();
    journal.start(record);
    assertNull(journal.all(runId).get(0).args());
  }

  @Test
  void rejectsNullStart() {
    assertThrows(NullPointerException.class, () -> journal.start(null));
  }

  @Test
  void rejectsNullCompleteRunId() {
    assertThrows(NullPointerException.class, () -> journal.complete(null, "c1", "ok"));
  }

  @Test
  void rejectsNullCompleteCallId() {
    assertThrows(NullPointerException.class, () -> journal.complete(Ids.newId(), null, "ok"));
  }

  @Test
  void rejectsNullFailRunId() {
    assertThrows(NullPointerException.class, () -> journal.fail(null, "c1", "boom"));
  }

  @Test
  void rejectsNullFailCallId() {
    assertThrows(NullPointerException.class, () -> journal.fail(Ids.newId(), null, "boom"));
  }
}
