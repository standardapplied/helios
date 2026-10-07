/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.Ids;
import com.standardapplied.helios.core.runtime.ToolCallJournal;
import com.standardapplied.helios.core.runtime.ToolCallRecord;
import com.standardapplied.helios.core.test.ToolCallJournalContract;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PgToolCallJournalTest extends ToolCallJournalContract {

  @Override
  protected ToolCallJournal createJournal() {
    PgTestSupport.truncateRuntime();
    return new PgToolCallJournal(PgTestSupport.pgConfig());
  }

  @Override
  protected UUID newRunId() {
    return PgTestSupport.newSeededRunId();
  }

  @Test
  void inflightOnNullRunIsEmpty() {
    assertTrue(journal.inflight(null).isEmpty());
  }

  @Test
  void allOnNullRunIsEmpty() {
    assertTrue(journal.all(null).isEmpty());
  }

  @Test
  void duplicateInsertOnSameKeyThrows() {
    var runId = PgTestSupport.newSeededRunId();
    var record = started(runId, "c1", "send");
    journal.start(record);
    assertThrows(PgException.class, () -> journal.start(record));
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
    var registry = new com.standardapplied.helios.core.common.SecretRegistry();
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
  void argsRedactionWalksNestedMapsAndLists() {
    // The PgToolCallJournal.start path applies the redactor to each string leaf BEFORE Jackson
    // serialises the args map. This test exercises the deep walk: the secret appears inside a
    // nested map AND inside a list element, neither of which a top-level redaction pass would
    // reach. Both occurrences must surface as <redacted:API_KEY> in the round-tripped args.
    var registry = new com.standardapplied.helios.core.common.SecretRegistry();
    var secret = "sk-supersecret-abc12345";
    registry.register("API_KEY", secret);
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
            .withToolName("call")
            .withArgs(
                Map.of(
                    "headers", Map.of("Authorization", "Bearer " + secret),
                    "items", List.of("alpha", secret, "omega"),
                    "count", 7))
            .withStartedAt(Ids.now())
            .build());

    var rec = redactingJournal.all(runId).get(0);
    var argsAsString = String.valueOf(rec.args());
    assertFalse(
        argsAsString.contains(secret),
        "deep-walk redaction should remove the secret from every nested string leaf;"
            + " args="
            + argsAsString);
    var marker = "<redacted:API_KEY>";
    assertTrue(
        argsAsString.contains("Bearer " + marker),
        "marker must appear inside the nested header map; args=" + argsAsString);
    assertTrue(
        argsAsString.contains(marker),
        "marker must appear inside the list element; args=" + argsAsString);
  }

  @Test
  void argsRedactionPassesThroughWhenNoRedactorConfigured() {
    // Mirror property: with no redactor, the args walk is a no-op fast path that returns the
    // same map reference. Persisted args are verbatim — the default behaviour callers rely on
    // for evals and debugging.
    var defaultJournal = new PgToolCallJournal(PgTestSupport.pgConfig());
    var runId = PgTestSupport.newSeededRunId();
    var raw = "sk-not-redacted-1234567";
    defaultJournal.start(
        ToolCallRecord.newBuilder()
            .withRunId(runId)
            .withIteration(0)
            .withToolCallId("c1")
            .withToolName("call")
            .withArgs(Map.of("token", raw, "headers", Map.of("Authorization", raw)))
            .withStartedAt(Ids.now())
            .build());
    var argsAsString = String.valueOf(defaultJournal.all(runId).get(0).args());
    assertTrue(argsAsString.contains(raw), "no-redactor path should leave args verbatim");
  }

  @Test
  void failErrorIsScrubbedWhenRedactorConfigured() {
    var registry = new com.standardapplied.helios.core.common.SecretRegistry();
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
