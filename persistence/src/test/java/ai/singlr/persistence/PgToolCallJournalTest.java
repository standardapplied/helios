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
import ai.singlr.core.runtime.ToolCallRecord;
import ai.singlr.core.runtime.ToolCallStatus;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PgToolCallJournalTest {

  private PgToolCallJournal journal;

  @BeforeEach
  void setUp() {
    PgTestSupport.truncateRuntime();
    journal = new PgToolCallJournal(PgTestSupport.pgConfig());
  }

  private static ToolCallRecord started(UUID runId, String callId, String toolName) {
    return ToolCallRecord.newBuilder()
        .withRunId(runId)
        .withIteration(0)
        .withToolCallId(callId)
        .withToolName(toolName)
        .withArgs(Map.of("k", "v"))
        .withStartedAt(Ids.now())
        .build();
  }

  @Test
  void startThenComplete() {
    var runId = PgTestSupport.newSeededRunId();
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
    var runId = PgTestSupport.newSeededRunId();
    journal.start(started(runId, "c1", "weather"));
    journal.fail(runId, "c1", "boom");

    var rec = journal.all(runId).get(0);
    assertEquals(ToolCallStatus.FAILED, rec.status());
    assertEquals("boom", rec.error());
    assertNull(rec.output());
  }

  @Test
  void inflightExcludesTerminal() {
    var runId = PgTestSupport.newSeededRunId();
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
  void completeNoMatchIsNoOp() {
    var runId = PgTestSupport.newSeededRunId();
    journal.complete(runId, "missing", "irrelevant");
    assertTrue(journal.all(runId).isEmpty());
  }

  @Test
  void completeAfterTerminalIsNoOp() {
    var runId = PgTestSupport.newSeededRunId();
    journal.start(started(runId, "c1", "send"));
    journal.fail(runId, "c1", "first");
    journal.complete(runId, "c1", "second");
    var rec = journal.all(runId).get(0);
    assertEquals(ToolCallStatus.FAILED, rec.status());
    assertEquals("first", rec.error());
  }

  @Test
  void inflightUnknownReturnsEmpty() {
    assertTrue(journal.inflight(Ids.newId()).isEmpty());
    assertTrue(journal.inflight(null).isEmpty());
  }

  @Test
  void allUnknownReturnsEmpty() {
    assertTrue(journal.all(Ids.newId()).isEmpty());
    assertTrue(journal.all(null).isEmpty());
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

  @Test
  void duplicateInsertOnSameKeyThrows() {
    var runId = PgTestSupport.newSeededRunId();
    var record = started(runId, "c1", "send");
    journal.start(record);
    assertThrows(PgException.class, () -> journal.start(record));
  }

  @Test
  void argsNullPersistsAsNull() {
    var runId = PgTestSupport.newSeededRunId();
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

  // ── opt-in journal-side redaction (PgConfig.withRedactor) ─────────────────

  @Test
  void toolCallFieldsPersistedVerbatimByDefault() {
    var runId = PgTestSupport.newSeededRunId();
    var raw = "ghp_supersecret_12345678";
    journal.start(
        ToolCallRecord.newBuilder()
            .withRunId(runId)
            .withIteration(0)
            .withToolCallId("c1")
            .withToolName("gh-cli")
            .withArgs(Map.of("token", raw))
            .withStartedAt(Ids.now())
            .build());
    journal.complete(runId, "c1", "auth ok: " + raw);

    var record = journal.all(runId).get(0);
    assertTrue(record.args().toString().contains(raw), "default: args verbatim");
    assertEquals("auth ok: " + raw, record.output(), "default: output verbatim");
  }

  @Test
  void toolCallFieldsAreScrubbedWhenRedactorConfigured() {
    var registry = new ai.singlr.core.common.SecretRegistry();
    registry.register("GH_TOKEN", "ghp_supersecret_12345678");
    var redactingJournal =
        new PgToolCallJournal(
            PgConfig.newBuilder()
                .withDbClient(PgTestSupport.dbClient())
                .withRedactor(registry.redactor())
                .build());

    var runId = PgTestSupport.newSeededRunId();
    redactingJournal.start(
        ToolCallRecord.newBuilder()
            .withRunId(runId)
            .withIteration(0)
            .withToolCallId("c1")
            .withToolName("gh-cli")
            .withArgs(Map.of("token", "ghp_supersecret_12345678"))
            .withStartedAt(Ids.now())
            .build());
    redactingJournal.complete(runId, "c1", "auth ok: ghp_supersecret_12345678");

    var record = redactingJournal.all(runId).get(0);
    var marker = "<redacted:GH_TOKEN>";
    assertTrue(
        record.args().toString().contains(marker)
            && !record.args().toString().contains("ghp_supersecret"),
        "args JSON should be scrubbed of the registered secret; got: " + record.args());
    assertEquals(
        "auth ok: " + marker, record.output(), "output should be scrubbed of registered secret");
  }

  @Test
  void failErrorIsScrubbedWhenRedactorConfigured() {
    var registry = new ai.singlr.core.common.SecretRegistry();
    registry.register("API_KEY", "sk-supersecret-abc12345");
    var redactingJournal =
        new PgToolCallJournal(
            PgConfig.newBuilder()
                .withDbClient(PgTestSupport.dbClient())
                .withRedactor(registry.redactor())
                .build());

    var runId = PgTestSupport.newSeededRunId();
    redactingJournal.start(started(runId, "c1", "lookup"));
    redactingJournal.fail(runId, "c1", "401 Unauthorized: token=sk-supersecret-abc12345");

    var record = redactingJournal.all(runId).get(0);
    assertEquals(
        "401 Unauthorized: token=<redacted:API_KEY>",
        record.error(),
        "fail() error path should be scrubbed");
  }
}
