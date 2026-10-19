/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.model.ToolCall;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class PreToolUseResultTest {

  private static final ToolCall CALL = new ToolCall("c", "read", Map.of());
  private static final HookDecision MUTATION =
      HookDecision.of("A", HookOutcome.mutateArgs(Map.of("path", "a")));

  @Test
  void theMutationsAreCopied() {
    var mutations = new ArrayList<>(List.of(MUTATION));
    var result = new PreToolUseResult(CALL, mutations, HookDecision.proceed());
    mutations.clear();
    assertEquals(List.of(MUTATION), result.mutations());
  }

  @Test
  void aNullComponentIsRejected() {
    var none = List.<HookDecision>of();
    var proceed = HookDecision.proceed();
    assertEquals(
        "call must not be null",
        assertThrows(NullPointerException.class, () -> new PreToolUseResult(null, none, proceed))
            .getMessage());
    assertEquals(
        "mutations must not be null",
        assertThrows(NullPointerException.class, () -> new PreToolUseResult(CALL, null, proceed))
            .getMessage());
    assertEquals(
        "decision must not be null",
        assertThrows(NullPointerException.class, () -> new PreToolUseResult(CALL, none, null))
            .getMessage());
  }

  @Test
  void aMutationThatIsNotAnArgumentMutationIsRejected() {
    var block = HookDecision.of("B", HookOutcome.block("no"));
    var mutations = List.of(block);
    var proceed = HookDecision.proceed();
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> new PreToolUseResult(CALL, mutations, proceed));
    assertEquals("not an argument mutation: " + block, ex.getMessage());
  }

  @Test
  void anArgumentMutationCannotEndTheChain() {
    var none = List.<HookDecision>of();
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> new PreToolUseResult(CALL, none, MUTATION));
    assertEquals("an argument mutation does not end the chain", ex.getMessage());
  }
}
