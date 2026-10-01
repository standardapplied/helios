/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.ToolCall;
import ai.singlr.core.tool.ToolResult;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests for the 5 tool/hook QueryEvent subtypes added in Phase 2 part 2b. */
final class QueryEventToolAndHookTest {

  private static final String SID = "sess-1";
  private static final long TURN = 3;
  private static final Instant TS = Instant.parse("2026-05-14T20:00:00Z");
  private static final ToolCall CALL = new ToolCall("c1", "Read", Map.of("path", "/x"));

  // ── ToolUse ──────────────────────────────────────────────────────────────

  @Test
  void toolUseAccessorsExposeFields() {
    var e = new QueryEvent.ToolUse(SID, TURN, TS, CALL);
    assertEquals(SID, e.sessionId());
    assertEquals(TURN, e.turnIndex());
    assertEquals(TS, e.timestamp());
    assertSame(CALL, e.call());
  }

  @Test
  void toolUseRejectsNullCall() {
    var ex =
        assertThrows(NullPointerException.class, () -> new QueryEvent.ToolUse(SID, TURN, TS, null));
    assertEquals("call must not be null", ex.getMessage());
  }

  // ── ToolResult ──────────────────────────────────────────────────────────

  @Test
  void toolResultAccessorsExposeFields() {
    var r = ToolResult.success("done");
    var e = new QueryEvent.ToolResult(SID, TURN, TS, CALL, r);
    assertSame(CALL, e.call());
    assertSame(r, e.result());
  }

  @Test
  void toolResultRejectsNullCall() {
    var r = ToolResult.success("done");
    var ex =
        assertThrows(
            NullPointerException.class, () -> new QueryEvent.ToolResult(SID, TURN, TS, null, r));
    assertEquals("call must not be null", ex.getMessage());
  }

  @Test
  void toolResultRejectsNullResult() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> new QueryEvent.ToolResult(SID, TURN, TS, CALL, null));
    assertEquals("result must not be null", ex.getMessage());
  }

  // ── ToolBlocked ──────────────────────────────────────────────────────────

  @Test
  void toolBlockedAccessorsExposeFields() {
    var e = new QueryEvent.ToolBlocked(SID, TURN, TS, CALL, "PermissionHook", "denied by policy");
    assertEquals("PermissionHook", e.hookName());
    assertEquals("denied by policy", e.reason());
    assertSame(CALL, e.call());
  }

  @Test
  void toolBlockedRejectsNullCall() {
    assertThrows(
        NullPointerException.class,
        () -> new QueryEvent.ToolBlocked(SID, TURN, TS, null, "h", "r"));
  }

  @Test
  void toolBlockedRejectsNullHookName() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new QueryEvent.ToolBlocked(SID, TURN, TS, CALL, null, "r"));
    assertEquals("hookName must not be null", ex.getMessage());
  }

  @Test
  void toolBlockedRejectsBlankHookName() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new QueryEvent.ToolBlocked(SID, TURN, TS, CALL, "  ", "r"));
    assertEquals("hookName must not be blank", ex.getMessage());
  }

  @Test
  void toolBlockedRejectsNullReason() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new QueryEvent.ToolBlocked(SID, TURN, TS, CALL, "h", null));
    assertEquals("reason must not be null", ex.getMessage());
  }

  @Test
  void toolBlockedRejectsBlankReason() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new QueryEvent.ToolBlocked(SID, TURN, TS, CALL, "h", ""));
    assertEquals("reason must not be blank", ex.getMessage());
  }

  // ── ToolMutated ──────────────────────────────────────────────────────────

  @Test
  void toolMutatedAccessorsExposeFields() {
    Map<String, Object> before = Map.of("path", "/before");
    Map<String, Object> after = Map.of("path", "/after");
    var e = new QueryEvent.ToolMutated(SID, TURN, TS, CALL, "Sanitizer", before, after);
    assertEquals("Sanitizer", e.hookName());
    assertEquals("/before", e.inputBefore().get("path"));
    assertEquals("/after", e.inputAfter().get("path"));
  }

  @Test
  void toolMutatedDefensivelyCopiesInputs() {
    var before = new HashMap<String, Object>();
    before.put("k", "v");
    var after = new HashMap<String, Object>();
    after.put("k", "v2");
    var e = new QueryEvent.ToolMutated(SID, TURN, TS, CALL, "h", before, after);
    before.put("k", "tampered");
    after.put("k", "tampered");
    assertEquals("v", e.inputBefore().get("k"), "inputBefore must be defensively copied");
    assertEquals("v2", e.inputAfter().get("k"), "inputAfter must be defensively copied");
  }

  @Test
  void toolMutatedRejectsNullCall() {
    assertThrows(
        NullPointerException.class,
        () -> new QueryEvent.ToolMutated(SID, TURN, TS, null, "h", Map.of(), Map.of()));
  }

  @Test
  void toolMutatedRejectsNullHookName() {
    assertThrows(
        NullPointerException.class,
        () -> new QueryEvent.ToolMutated(SID, TURN, TS, CALL, null, Map.of(), Map.of()));
  }

  @Test
  void toolMutatedRejectsBlankHookName() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new QueryEvent.ToolMutated(SID, TURN, TS, CALL, "  ", Map.of(), Map.of()));
    assertEquals("hookName must not be blank", ex.getMessage());
  }

  @Test
  void toolMutatedRejectsNullInputBefore() {
    assertThrows(
        NullPointerException.class,
        () -> new QueryEvent.ToolMutated(SID, TURN, TS, CALL, "h", null, Map.of()));
  }

  @Test
  void toolMutatedRejectsNullInputAfter() {
    assertThrows(
        NullPointerException.class,
        () -> new QueryEvent.ToolMutated(SID, TURN, TS, CALL, "h", Map.of(), null));
  }

  // ── HookFired ───────────────────────────────────────────────────────────

  @Test
  void hookFiredAccessorsExposeFields() {
    var e = new QueryEvent.HookFired(SID, TURN, TS, "MyHook", "PreToolUseHook", "Block");
    assertEquals("MyHook", e.hookName());
    assertEquals("PreToolUseHook", e.phase());
    assertEquals("Block", e.outcomeKind());
  }

  @Test
  void hookFiredRejectsNullHookName() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new QueryEvent.HookFired(SID, TURN, TS, null, "p", "Block"));
    assertEquals("hookName must not be null", ex.getMessage());
  }

  @Test
  void hookFiredRejectsBlankHookName() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new QueryEvent.HookFired(SID, TURN, TS, "  ", "p", "Block"));
    assertEquals("hookName must not be blank", ex.getMessage());
  }

  @Test
  void hookFiredRejectsNullPhase() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new QueryEvent.HookFired(SID, TURN, TS, "h", null, "Block"));
    assertEquals("phase must not be null", ex.getMessage());
  }

  @Test
  void hookFiredRejectsBlankPhase() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new QueryEvent.HookFired(SID, TURN, TS, "h", "", "Block"));
    assertEquals("phase must not be blank", ex.getMessage());
  }

  @Test
  void hookFiredRejectsNullOutcomeKind() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new QueryEvent.HookFired(SID, TURN, TS, "h", "p", null));
    assertEquals("outcomeKind must not be null", ex.getMessage());
  }

  @Test
  void hookFiredRejectsBlankOutcomeKind() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new QueryEvent.HookFired(SID, TURN, TS, "h", "p", "  "));
    assertEquals("outcomeKind must not be blank", ex.getMessage());
  }

  // ── sealed permits validation ────────────────────────────────────────────

  @Test
  void allFiveNewSubtypesAreQueryEvents() {
    var u = new QueryEvent.ToolUse(SID, TURN, TS, CALL);
    var r = new QueryEvent.ToolResult(SID, TURN, TS, CALL, ToolResult.success("x"));
    var b = new QueryEvent.ToolBlocked(SID, TURN, TS, CALL, "h", "r");
    var m = new QueryEvent.ToolMutated(SID, TURN, TS, CALL, "h", Map.of(), Map.of());
    var f = new QueryEvent.HookFired(SID, TURN, TS, "h", "p", "Block");
    assertTrue(u instanceof QueryEvent);
    assertTrue(r instanceof QueryEvent);
    assertTrue(b instanceof QueryEvent);
    assertTrue(m instanceof QueryEvent);
    assertTrue(f instanceof QueryEvent);
  }
}
