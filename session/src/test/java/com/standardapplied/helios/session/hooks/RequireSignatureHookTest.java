/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.hooks;

import static com.standardapplied.helios.session.test.HookInputs.call;
import static com.standardapplied.helios.session.test.HookInputs.context;
import static com.standardapplied.helios.session.test.HookInputs.stopResponse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class RequireSignatureHookTest {

  // ── Signature record ────────────────────────────────────────────────────

  @Test
  void signatureRejectsBlankName() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new RequireSignatureHook.Signature("  ", "d", c -> true));
    assertEquals("name must not be blank", ex.getMessage());
  }

  @Test
  void signatureRejectsBlankDescription() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new RequireSignatureHook.Signature("n", " ", c -> true));
    assertEquals("description must not be blank", ex.getMessage());
  }

  @Test
  void signatureRejectsNullPredicate() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> new RequireSignatureHook.Signature("n", "d", null));
    assertEquals("matches must not be null", ex.getMessage());
  }

  @Test
  void ofToolNameRejectsBlankToolName() {
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> RequireSignatureHook.Signature.ofToolName(" "));
    assertEquals("toolName must not be blank", ex.getMessage());
  }

  @Test
  void ofToolNameProducesPredicateMatchingByName() {
    var sig = RequireSignatureHook.Signature.ofToolName("Search");
    assertTrue(sig.matches().test(call("Search")));
    assertTrue(!sig.matches().test(call("Other")));
  }

  // ── factory shortcut ────────────────────────────────────────────────────

  @Test
  void requiringToolNameRejectsBlank() {
    var ex =
        assertThrows(IllegalArgumentException.class, () -> RequireSignatureHook.withToolName(""));
    assertEquals("toolName must not be blank", ex.getMessage());
  }

  @Test
  void requiringToolNameBlocksStopUntilCalled() {
    var hook = RequireSignatureHook.withToolName("Search");
    assertInstanceOf(HookOutcome.Inject.class, hook.beforeStop(stopResponse(), context()));
    hook.afterTool(call("Search"), ToolResult.success("hits"), context());
    assertInstanceOf(HookOutcome.Continue.class, hook.beforeStop(stopResponse(), context()));
  }

  @Test
  void unrelatedToolDoesNotSatisfy() {
    var hook = RequireSignatureHook.withToolName("Search");
    hook.afterTool(call("Other"), ToolResult.success("x"), context());
    assertInstanceOf(HookOutcome.Inject.class, hook.beforeStop(stopResponse(), context()));
  }

  // ── builder ─────────────────────────────────────────────────────────────

  @Test
  void builderRequiresAtLeastOneSignature() {
    var b = RequireSignatureHook.newBuilder();
    var ex = assertThrows(IllegalStateException.class, b::build);
    assertEquals("at least one signature is required", ex.getMessage());
  }

  @Test
  void builderAccumulatesMultipleSignatures() {
    var hook =
        RequireSignatureHook.newBuilder().withToolName("Search").withToolName("Submit").build();
    assertEquals(2, hook.signatures().size());
  }

  @Test
  void multipleSignaturesAllMustBeMet() {
    var hook =
        RequireSignatureHook.newBuilder().withToolName("Search").withToolName("Submit").build();
    hook.afterTool(call("Search"), ToolResult.success("x"), context());
    var halfwayDecision = hook.beforeStop(stopResponse(), context());
    assertInstanceOf(HookOutcome.Inject.class, halfwayDecision);
    assertTrue(((HookOutcome.Inject) halfwayDecision).userMessage().contains("Submit"));
    hook.afterTool(call("Submit"), ToolResult.success("x"), context());
    assertInstanceOf(HookOutcome.Continue.class, hook.beforeStop(stopResponse(), context()));
  }

  @Test
  void injectMessageListsEveryUnmetSignature() {
    var hook =
        RequireSignatureHook.newBuilder().withToolName("Search").withToolName("Submit").build();
    var decision = hook.beforeStop(stopResponse(), context());
    var msg = ((HookOutcome.Inject) decision).userMessage();
    assertTrue(msg.contains("Search"));
    assertTrue(msg.contains("Submit"));
    assertTrue(msg.startsWith("Cannot stop yet"));
  }

  @Test
  void customPredicateRequirementHonored() {
    var hook =
        RequireSignatureHook.newBuilder()
            .withSignature(
                "submitWithFlag",
                "Submit with debug=true",
                c -> c.name().equals("Submit") && Boolean.TRUE.equals(c.arguments().get("debug")))
            .build();
    hook.afterTool(
        new ToolCall("c1", "Submit", Map.of("debug", false)), ToolResult.success("x"), context());
    assertInstanceOf(HookOutcome.Inject.class, hook.beforeStop(stopResponse(), context()));
    hook.afterTool(
        new ToolCall("c2", "Submit", Map.of("debug", true)), ToolResult.success("x"), context());
    assertInstanceOf(HookOutcome.Continue.class, hook.beforeStop(stopResponse(), context()));
  }

  @Test
  void requiringSignatureRejectsNull() {
    var b = RequireSignatureHook.newBuilder();
    var ex = assertThrows(NullPointerException.class, () -> b.withSignature(null));
    assertEquals("signature must not be null", ex.getMessage());
  }

  // ── observation state ───────────────────────────────────────────────────

  @Test
  void observedSnapshotIsImmutableCopy() {
    var hook = RequireSignatureHook.withToolName("Search");
    hook.afterTool(call("Search"), ToolResult.success("x"), context());
    var snap = hook.observed();
    assertEquals(java.util.Set.of("Search"), snap);
    assertThrows(UnsupportedOperationException.class, () -> snap.add("Other"));
  }

  // ── name + priority ─────────────────────────────────────────────────────

  @Test
  void hookCarriesItsClassName() {
    var hook = RequireSignatureHook.withToolName("Search");
    assertEquals("RequireSignatureHook", hook.name());
  }

  @Test
  void hookFiresAtBothPostToolAndPreStopPhases() {
    var hook = RequireSignatureHook.withToolName("X");
    assertInstanceOf(PostToolUseHook.class, hook);
    assertInstanceOf(PreStopHook.class, hook);
  }
}
