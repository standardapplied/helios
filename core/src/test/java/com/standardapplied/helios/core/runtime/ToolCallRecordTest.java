/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.Ids;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolCallRecordTest {

  @Test
  void builderHappyPath() {
    var runId = Ids.newId();
    var now = Ids.now();
    var args = Map.<String, Object>of("query", "weather");

    var record =
        ToolCallRecord.newBuilder()
            .withRunId(runId)
            .withIteration(2)
            .withToolCallId("call_42")
            .withToolName("get_weather")
            .withArgs(args)
            .withStatus(ToolCallStatus.STARTED)
            .withStartedAt(now)
            .build();

    assertEquals(runId, record.runId());
    assertEquals(2, record.iteration());
    assertEquals("call_42", record.toolCallId());
    assertEquals("get_weather", record.toolName());
    assertEquals(args, record.args());
    assertEquals(ToolCallStatus.STARTED, record.status());
    assertEquals(now, record.startedAt());
    assertNull(record.output());
    assertNull(record.error());
    assertNull(record.endedAt());
  }

  @Test
  void terminalSucceeded() {
    var record =
        ToolCallRecord.newBuilder()
            .withRunId(Ids.newId())
            .withToolCallId("c")
            .withToolName("t")
            .withStatus(ToolCallStatus.SUCCEEDED)
            .withOutput("ok")
            .withStartedAt(Ids.now())
            .withEndedAt(Ids.now())
            .build();
    assertEquals(ToolCallStatus.SUCCEEDED, record.status());
    assertEquals("ok", record.output());
    assertNotNull(record.endedAt());
  }

  @Test
  void terminalFailed() {
    var record =
        ToolCallRecord.newBuilder()
            .withRunId(Ids.newId())
            .withToolCallId("c")
            .withToolName("t")
            .withStatus(ToolCallStatus.FAILED)
            .withError("boom")
            .withStartedAt(Ids.now())
            .withEndedAt(Ids.now())
            .build();
    assertEquals(ToolCallStatus.FAILED, record.status());
    assertEquals("boom", record.error());
  }

  @Test
  void argsCopiedDefensively() {
    var mutable = new java.util.HashMap<String, Object>();
    mutable.put("k", "v");
    var record =
        ToolCallRecord.newBuilder()
            .withRunId(Ids.newId())
            .withToolCallId("c")
            .withToolName("t")
            .withArgs(mutable)
            .withStartedAt(Ids.now())
            .build();
    mutable.put("k2", "v2");
    assertEquals(1, record.args().size());
    assertThrows(UnsupportedOperationException.class, () -> record.args().put("x", "y"));
  }

  @Test
  void argsAllowNullValues() {
    var args = new java.util.HashMap<String, Object>();
    args.put("required", "x");
    args.put("optional", null);
    var record =
        ToolCallRecord.newBuilder()
            .withRunId(Ids.newId())
            .withToolCallId("c")
            .withToolName("t")
            .withArgs(args)
            .withStartedAt(Ids.now())
            .build();
    assertEquals(2, record.args().size());
    assertNull(record.args().get("optional"));
    assertEquals("x", record.args().get("required"));
  }

  @Test
  void nullArgsAllowed() {
    var record =
        ToolCallRecord.newBuilder()
            .withRunId(Ids.newId())
            .withToolCallId("c")
            .withToolName("t")
            .withArgs(null)
            .withStartedAt(Ids.now())
            .build();
    assertNull(record.args());
  }

  @Test
  void builderRoundtrip() {
    var original =
        ToolCallRecord.newBuilder()
            .withRunId(Ids.newId())
            .withIteration(1)
            .withToolCallId("c")
            .withToolName("t")
            .withArgs(Map.of("a", 1))
            .withStatus(ToolCallStatus.SUCCEEDED)
            .withOutput("out")
            .withStartedAt(Ids.now())
            .withEndedAt(Ids.now())
            .build();
    var copy = ToolCallRecord.newBuilder(original).build();
    assertEquals(original, copy);
  }

  @Test
  void rejectsNullRunId() {
    assertThrows(
        NullPointerException.class,
        () ->
            ToolCallRecord.newBuilder()
                .withToolCallId("c")
                .withToolName("t")
                .withStartedAt(Ids.now())
                .build());
  }

  @Test
  void rejectsNullToolCallId() {
    assertThrows(
        NullPointerException.class,
        () ->
            ToolCallRecord.newBuilder()
                .withRunId(Ids.newId())
                .withToolName("t")
                .withStartedAt(Ids.now())
                .build());
  }

  @Test
  void rejectsNullToolName() {
    assertThrows(
        NullPointerException.class,
        () ->
            ToolCallRecord.newBuilder()
                .withRunId(Ids.newId())
                .withToolCallId("c")
                .withStartedAt(Ids.now())
                .build());
  }

  @Test
  void rejectsNullStatus() {
    assertThrows(
        NullPointerException.class,
        () ->
            ToolCallRecord.newBuilder()
                .withRunId(Ids.newId())
                .withToolCallId("c")
                .withToolName("t")
                .withStatus(null)
                .withStartedAt(Ids.now())
                .build());
  }

  @Test
  void rejectsNullStartedAt() {
    assertThrows(
        NullPointerException.class,
        () ->
            ToolCallRecord.newBuilder()
                .withRunId(Ids.newId())
                .withToolCallId("c")
                .withToolName("t")
                .build());
  }

  @Test
  void rejectsNegativeIteration() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolCallRecord.newBuilder()
                .withRunId(Ids.newId())
                .withIteration(-1)
                .withToolCallId("c")
                .withToolName("t")
                .withStartedAt(Ids.now())
                .build());
  }

  @Test
  void toolCallStatusEnumValues() {
    assertSame(ToolCallStatus.STARTED, ToolCallStatus.valueOf("STARTED"));
    assertSame(ToolCallStatus.SUCCEEDED, ToolCallStatus.valueOf("SUCCEEDED"));
    assertSame(ToolCallStatus.FAILED, ToolCallStatus.valueOf("FAILED"));
    assertEquals(3, ToolCallStatus.values().length);
  }

  @Test
  void unsafeResumePolicyEnumValues() {
    assertSame(UnsafeResumePolicy.FAIL_LOUD, UnsafeResumePolicy.valueOf("FAIL_LOUD"));
    assertSame(
        UnsafeResumePolicy.AUTO_FAIL_AND_CONTINUE,
        UnsafeResumePolicy.valueOf("AUTO_FAIL_AND_CONTINUE"));
    assertEquals(2, UnsafeResumePolicy.values().length);
  }

  @Test
  void unsafeResumeExceptionMessageNamesCalls() {
    var record =
        ToolCallRecord.newBuilder()
            .withRunId(Ids.newId())
            .withToolCallId("call_x")
            .withToolName("send_email")
            .withStartedAt(Ids.now())
            .build();
    var ex = new UnsafeResumeException(java.util.List.of(record));
    assertTrue(ex.getMessage().contains("send_email"));
    assertTrue(ex.getMessage().contains("call_x"));
    assertEquals(1, ex.unsafeCalls().size());
    assertEquals(record, ex.unsafeCalls().get(0));
  }
}
