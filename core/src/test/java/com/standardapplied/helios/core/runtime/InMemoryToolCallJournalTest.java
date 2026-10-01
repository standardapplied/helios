/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.Ids;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InMemoryToolCallJournalTest {

  private final InMemoryToolCallJournal journal = new InMemoryToolCallJournal();

  private static ToolCallRecord started(UUID runId, String callId, String toolName) {
    return ToolCallRecord.newBuilder()
        .withRunId(runId)
        .withToolCallId(callId)
        .withToolName(toolName)
        .withStartedAt(Ids.now())
        .build();
  }

  @Test
  void startThenComplete() {
    var runId = Ids.newId();
    journal.start(started(runId, "c1", "weather"));
    journal.complete(runId, "c1", "sunny");

    var all = journal.all(runId);
    assertEquals(1, all.size());
    assertEquals(ToolCallStatus.SUCCEEDED, all.get(0).status());
    assertEquals("sunny", all.get(0).output());
    assertNotNull(all.get(0).endedAt());
    assertNull(all.get(0).error());
  }

  @Test
  void startThenFail() {
    var runId = Ids.newId();
    journal.start(started(runId, "c1", "weather"));
    journal.fail(runId, "c1", "timeout");

    var all = journal.all(runId);
    assertEquals(ToolCallStatus.FAILED, all.get(0).status());
    assertEquals("timeout", all.get(0).error());
    assertNull(all.get(0).output());
  }

  @Test
  void inflightExcludesTerminal() {
    var runId = Ids.newId();
    journal.start(started(runId, "c1", "weather"));
    journal.start(started(runId, "c2", "weather"));
    journal.start(started(runId, "c3", "weather"));
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
    var runId = Ids.newId();
    journal.start(started(runId, "c1", "weather"));
    journal.start(started(runId, "c2", "weather"));
    var all = journal.all(runId);
    assertEquals("c1", all.get(0).toolCallId());
    assertEquals("c2", all.get(1).toolCallId());
  }

  @Test
  void completeOnUnknownRunIsNoOp() {
    journal.complete(Ids.newId(), "c1", "ok");
    // No exception, no state mutation — matches PgToolCallJournal semantics.
  }

  @Test
  void completeOnUnknownCallIdIsNoOp() {
    var runId = Ids.newId();
    journal.start(started(runId, "c1", "weather"));
    journal.complete(runId, "missing", "ok");
    var rec = journal.all(runId).get(0);
    assertEquals(ToolCallStatus.STARTED, rec.status());
  }

  @Test
  void completeAlreadyTerminalIsNoOp() {
    var runId = Ids.newId();
    journal.start(started(runId, "c1", "weather"));
    journal.complete(runId, "c1", "ok");
    journal.complete(runId, "c1", "ok again");
    var rec = journal.all(runId).get(0);
    assertEquals(ToolCallStatus.SUCCEEDED, rec.status());
    assertEquals("ok", rec.output());
  }

  @Test
  void failOnUnknownRunIsNoOp() {
    journal.fail(Ids.newId(), "c1", "boom");
    // No exception, no state mutation.
  }

  @Test
  void duplicateStartThrows() {
    var runId = Ids.newId();
    var first = started(runId, "c1", "send");
    journal.start(first);
    var second = started(runId, "c1", "send");
    assertThrows(IllegalStateException.class, () -> journal.start(second));
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
