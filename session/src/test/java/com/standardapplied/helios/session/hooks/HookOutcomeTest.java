/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.core.model.Message;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class HookOutcomeTest {

  @Test
  void contFactoryReturnsSingleton() {
    assertSame(HookOutcome.cont(), HookOutcome.cont());
    assertInstanceOf(HookOutcome.Continue.class, HookOutcome.cont());
  }

  // --- MutateArgs ---

  @Test
  void mutateArgsFactoryConstructsRecord() {
    var outcome = HookOutcome.mutateArgs(Map.of("k", "v"));
    var m = assertInstanceOf(HookOutcome.MutateArgs.class, outcome);
    assertEquals("v", m.args().get("k"));
  }

  @Test
  void mutateArgsDefensivelyCopies() {
    var src = new HashMap<String, Object>();
    src.put("k", "v");
    var outcome = (HookOutcome.MutateArgs) HookOutcome.mutateArgs(src);
    src.put("k", "changed");
    assertEquals("v", outcome.args().get("k"));
  }

  @Test
  void mutateArgsRejectsNull() {
    var ex = assertThrows(NullPointerException.class, () -> HookOutcome.mutateArgs(null));
    assertEquals("args must not be null", ex.getMessage());
  }

  // --- MutateHistory ---

  @Test
  void mutateHistoryFactoryConstructsRecord() {
    var history = List.of(Message.user("hello"));
    var outcome = HookOutcome.mutateHistory(history);
    var m = assertInstanceOf(HookOutcome.MutateHistory.class, outcome);
    assertEquals(1, m.history().size());
    assertEquals("hello", m.history().getFirst().content());
  }

  @Test
  void mutateHistoryDefensivelyCopies() {
    var src = new ArrayList<>(List.of(Message.user("a")));
    var outcome = (HookOutcome.MutateHistory) HookOutcome.mutateHistory(src);
    src.add(Message.user("b"));
    assertEquals(1, outcome.history().size());
  }

  @Test
  void mutateHistoryRejectsNull() {
    var ex = assertThrows(NullPointerException.class, () -> HookOutcome.mutateHistory(null));
    assertEquals("history must not be null", ex.getMessage());
  }

  @Test
  void mutateHistoryAcceptsEmptyList() {
    var outcome = (HookOutcome.MutateHistory) HookOutcome.mutateHistory(List.of());
    assertEquals(0, outcome.history().size());
  }

  // --- MutateText ---

  @Test
  void mutateTextFactoryConstructsRecord() {
    var outcome = HookOutcome.mutateText("rewritten");
    var m = assertInstanceOf(HookOutcome.MutateText.class, outcome);
    assertEquals("rewritten", m.text());
  }

  @Test
  void mutateTextRejectsNull() {
    var ex = assertThrows(NullPointerException.class, () -> HookOutcome.mutateText(null));
    assertEquals("text must not be null", ex.getMessage());
  }

  @Test
  void mutateTextRejectsBlank() {
    var ex = assertThrows(IllegalArgumentException.class, () -> HookOutcome.mutateText("  "));
    assertEquals("text must not be blank", ex.getMessage());
  }

  // --- MutateResult ---

  @Test
  void mutateResultFactoryConstructsRecord() {
    var outcome = HookOutcome.mutateResult("output text");
    var m = assertInstanceOf(HookOutcome.MutateResult.class, outcome);
    assertEquals("output text", m.output());
  }

  @Test
  void mutateResultRejectsNull() {
    var ex = assertThrows(NullPointerException.class, () -> HookOutcome.mutateResult(null));
    assertEquals("output must not be null", ex.getMessage());
  }

  @Test
  void mutateResultAcceptsEmptyString() {
    var outcome = (HookOutcome.MutateResult) HookOutcome.mutateResult("");
    assertEquals("", outcome.output());
  }

  // --- Block ---

  @Test
  void blockFactoryConstructsRecord() {
    var outcome = HookOutcome.block("nope");
    var b = assertInstanceOf(HookOutcome.Block.class, outcome);
    assertEquals("nope", b.reason());
  }

  @Test
  void blockRejectsNullReason() {
    var ex = assertThrows(NullPointerException.class, () -> HookOutcome.block(null));
    assertEquals("reason must not be null", ex.getMessage());
  }

  @Test
  void blockRejectsBlankReason() {
    var ex = assertThrows(IllegalArgumentException.class, () -> HookOutcome.block("  "));
    assertEquals("reason must not be blank", ex.getMessage());
  }

  // --- Inject ---

  @Test
  void injectFactoryConstructsRecord() {
    var outcome = HookOutcome.inject("please clarify");
    var i = assertInstanceOf(HookOutcome.Inject.class, outcome);
    assertEquals("please clarify", i.userMessage());
  }

  @Test
  void injectRejectsNullMessage() {
    var ex = assertThrows(NullPointerException.class, () -> HookOutcome.inject(null));
    assertEquals("userMessage must not be null", ex.getMessage());
  }

  @Test
  void injectRejectsBlankMessage() {
    var ex = assertThrows(IllegalArgumentException.class, () -> HookOutcome.inject(""));
    assertEquals("userMessage must not be blank", ex.getMessage());
  }

  // --- Stop ---

  @Test
  void stopFactoryConstructsRecord() {
    var outcome = HookOutcome.stop("done");
    var s = assertInstanceOf(HookOutcome.Stop.class, outcome);
    assertEquals("done", s.result());
  }

  @Test
  void stopRejectsNullResult() {
    var ex = assertThrows(NullPointerException.class, () -> HookOutcome.stop(null));
    assertEquals("result must not be null", ex.getMessage());
  }

  @Test
  void stopRejectsBlankResult() {
    var ex = assertThrows(IllegalArgumentException.class, () -> HookOutcome.stop(" "));
    assertEquals("result must not be blank", ex.getMessage());
  }

  @Test
  void continueRecordIsCheap() {
    var via = new HookOutcome.Continue();
    assertInstanceOf(HookOutcome.Continue.class, via);
  }
}
