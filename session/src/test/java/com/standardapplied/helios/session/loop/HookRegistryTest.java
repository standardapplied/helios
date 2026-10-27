/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.loop;

import static com.standardapplied.helios.session.test.HookInputs.named;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.hooks.DefaultHookContext;
import com.standardapplied.helios.session.hooks.Hook;
import com.standardapplied.helios.session.hooks.HookContext;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.OnStreamEventHook;
import com.standardapplied.helios.session.hooks.OnUserMessageHook;
import com.standardapplied.helios.session.hooks.PostModelTurnHook;
import com.standardapplied.helios.session.hooks.PostToolUseHook;
import com.standardapplied.helios.session.hooks.PreModelTurnHook;
import com.standardapplied.helios.session.hooks.PreStopHook;
import com.standardapplied.helios.session.hooks.PreToolUseHook;
import com.standardapplied.helios.session.test.LogCapture;
import com.standardapplied.helios.session.test.RedactedLogs;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class HookRegistryTest {

  private static final ToolCall CALL = new ToolCall("c", "read", Map.of());
  private static final ToolResult OK = ToolResult.success("done");

  private static HookContext ctx() {
    return new DefaultHookContext(
        "sess", 0, new CancellationToken(), ScriptedModel.newBuilder().build());
  }

  private static HookRegistry registry(List<? extends Hook> hooks) {
    return new HookRegistry(hooks, new SecretRegistry());
  }

  // ── construction ──────────────────────────────────────────────────────────

  @Test
  void constructorRejectsNullSecrets() {
    var hooks = List.<Hook>of();
    var ex = assertThrows(NullPointerException.class, () -> new HookRegistry(hooks, null));
    assertEquals("secrets must not be null", ex.getMessage());
  }

  @Test
  void constructorRejectsNullList() {
    var ex = assertThrows(NullPointerException.class, () -> registry(null));
    assertEquals("hooks must not be null", ex.getMessage());
  }

  @Test
  void constructorRejectsListContainingNull() {
    var list = new ArrayList<Hook>();
    list.add(null);
    var ex = assertThrows(NullPointerException.class, () -> registry(list));
    assertEquals("hooks must not contain null", ex.getMessage());
  }

  /** A {@code PreToolUse} hook that continues under the name {@code source} gives. */
  private record NamedBy(Supplier<String> source) implements PreToolUseHook {

    @Override
    public HookOutcome.PreToolUse beforeTool(ToolCall call, HookContext ctx) {
      return HookOutcome.cont();
    }

    @Override
    public String name() {
      return source.get();
    }
  }

  @Test
  void constructorRejectsANullName() {
    var hooks = List.of(new NamedBy(() -> null));
    var ex = assertThrows(NullPointerException.class, () -> registry(hooks));
    assertEquals("hook " + NamedBy.class.getName() + " returned a null name", ex.getMessage());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " \t"})
  void constructorRejectsABlankName(String name) {
    var hooks = List.of(new NamedBy(() -> name));
    var ex = assertThrows(IllegalArgumentException.class, () -> registry(hooks));
    assertEquals("hook " + NamedBy.class.getName() + " returned a blank name", ex.getMessage());
  }

  @Test
  void constructorRejectsTheReservedNamePermission() {
    var hooks = List.of(new NamedBy(() -> "Permission"));
    var ex = assertThrows(IllegalArgumentException.class, () -> registry(hooks));
    assertEquals(
        "hook " + NamedBy.class.getName() + " uses the reserved name Permission", ex.getMessage());
  }

  @Test
  void constructorPropagatesWhatANameThrows() {
    var failure = new IllegalStateException("no name");
    var hooks =
        List.of(
            new NamedBy(
                () -> {
                  throw failure;
                }));
    assertSame(failure, assertThrows(IllegalStateException.class, () -> registry(hooks)));
  }

  /** Blocks every call and rewrites every result, named {@code Gate} only on its first read. */
  private static final class TwoPhaseGate implements PreToolUseHook, PostToolUseHook {

    private final AtomicInteger reads = new AtomicInteger();

    @Override
    public HookOutcome.PreToolUse beforeTool(ToolCall call, HookContext ctx) {
      return HookOutcome.block("no");
    }

    @Override
    public HookOutcome.PostToolUse afterTool(ToolCall call, ToolResult result, HookContext ctx) {
      return HookOutcome.mutateResult("x");
    }

    @Override
    public String name() {
      return reads.getAndIncrement() == 0 ? "Gate" : null;
    }
  }

  @Test
  void aHookOfTwoPhasesHasItsNameReadOnceForBoth() {
    var gate = new TwoPhaseGate();
    var registry = registry(List.of(gate));
    assertEquals("Gate", registry.firePreToolUse(CALL, ctx()).decision().firingHookName());
    assertEquals("Gate", registry.firePostToolUse(CALL, OK, ctx()).firingHookName());
    assertEquals(1, gate.reads.get());
  }

  @Test
  void eachHookFiresOnlyInItsOwnPhaseInPriorityOrder() {
    var fired = new ArrayList<String>();
    PreToolUseHook a = (call, ctx) -> record(fired, "a", HookOutcome.cont());
    PreToolUseHook b = (call, ctx) -> record(fired, "b", HookOutcome.cont());
    PostToolUseHook c = (call, result, ctx) -> record(fired, "c", HookOutcome.cont());
    var registry = registry(List.of(a, b, c));

    registry.firePreToolUse(CALL, ctx());
    assertEquals(List.of("a", "b"), fired);
    registry.firePostToolUse(CALL, OK, ctx());
    assertEquals(List.of("a", "b", "c"), fired);
    var resp = Response.newBuilder().withContent("x").withFinishReason(FinishReason.STOP).build();
    registry.firePreStop(resp, ctx());
    registry.firePreModelTurn(List.of(Message.user("hi")), ctx());
    assertEquals(List.of("a", "b", "c"), fired);
  }

  private static <O extends HookOutcome> O record(List<String> fired, String name, O outcome) {
    fired.add(name);
    return outcome;
  }

  // ── empty registry returns Continue for every phase ──────────────────────

  @Test
  void firePreToolUseEmptyReturnsTheCallUnchanged() {
    var result = HookRegistry.empty().firePreToolUse(CALL, ctx());
    assertEquals(
        new PreToolUseResult(CALL, List.of(), new HookDecision<>(null, HookOutcome.cont())),
        result);
  }

  @Test
  void firePostToolUseEmptyReturnsContinue() {
    var decision = HookRegistry.empty().firePostToolUse(CALL, OK, ctx());
    assertInstanceOf(HookOutcome.Continue.class, decision.outcome());
  }

  @Test
  void firePreModelTurnEmptyReturnsContinue() {
    var decision = HookRegistry.empty().firePreModelTurn(List.of(Message.user("hi")), ctx());
    assertInstanceOf(HookOutcome.Continue.class, decision.outcome());
  }

  @Test
  void firePostModelTurnEmptyReturnsContinue() {
    var resp = Response.newBuilder().withContent("x").withFinishReason(FinishReason.STOP).build();
    var decision = HookRegistry.empty().firePostModelTurn(resp, ctx());
    assertInstanceOf(HookOutcome.Continue.class, decision.outcome());
  }

  @Test
  void firePreStopEmptyReturnsContinue() {
    var resp = Response.newBuilder().withContent("x").withFinishReason(FinishReason.STOP).build();
    var decision = HookRegistry.empty().firePreStop(resp, ctx());
    assertInstanceOf(HookOutcome.Continue.class, decision.outcome());
  }

  @Test
  void fireOnUserMessageEmptyReturnsContinue() {
    var decision = HookRegistry.empty().fireOnUserMessage(UserMessage.text("hi"), ctx());
    assertInstanceOf(HookOutcome.Continue.class, decision.outcome());
  }

  @Test
  void fireOnStreamEventEmptyIsNoOp() {
    HookRegistry.empty()
        .fireOnStreamEvent(new QueryEvent.AssistantText("sess", 1, Instant.now(), "x"), ctx());
  }

  // ── null-arg guards ──────────────────────────────────────────────────────

  @Test
  void firePreToolUseRejectsNullCall() {
    var registry = HookRegistry.empty();
    assertThrows(NullPointerException.class, () -> registry.firePreToolUse(null, ctx()));
  }

  @Test
  void firePreToolUseRejectsNullCtx() {
    var registry = HookRegistry.empty();
    assertThrows(NullPointerException.class, () -> registry.firePreToolUse(CALL, null));
  }

  @Test
  void firePostToolUseRejectsNullCall() {
    var registry = HookRegistry.empty();
    assertThrows(NullPointerException.class, () -> registry.firePostToolUse(null, OK, ctx()));
  }

  @Test
  void firePostToolUseRejectsNullResult() {
    var registry = HookRegistry.empty();
    assertThrows(NullPointerException.class, () -> registry.firePostToolUse(CALL, null, ctx()));
  }

  @Test
  void firePostToolUseRejectsNullCtx() {
    var registry = HookRegistry.empty();
    assertThrows(NullPointerException.class, () -> registry.firePostToolUse(CALL, OK, null));
  }

  @Test
  void firePreModelTurnRejectsNullHistory() {
    var registry = HookRegistry.empty();
    assertThrows(NullPointerException.class, () -> registry.firePreModelTurn(null, ctx()));
  }

  @Test
  void firePreModelTurnRejectsNullCtx() {
    var registry = HookRegistry.empty();
    assertThrows(NullPointerException.class, () -> registry.firePreModelTurn(List.of(), null));
  }

  @Test
  void firePostModelTurnRejectsNullResponse() {
    var registry = HookRegistry.empty();
    assertThrows(NullPointerException.class, () -> registry.firePostModelTurn(null, ctx()));
  }

  @Test
  void firePostModelTurnRejectsNullCtx() {
    var registry = HookRegistry.empty();
    var resp = Response.newBuilder().build();
    assertThrows(NullPointerException.class, () -> registry.firePostModelTurn(resp, null));
  }

  @Test
  void firePreStopRejectsNullResponse() {
    var registry = HookRegistry.empty();
    assertThrows(NullPointerException.class, () -> registry.firePreStop(null, ctx()));
  }

  @Test
  void firePreStopRejectsNullCtx() {
    var registry = HookRegistry.empty();
    var resp = Response.newBuilder().build();
    assertThrows(NullPointerException.class, () -> registry.firePreStop(resp, null));
  }

  @Test
  void fireOnUserMessageRejectsNullMsg() {
    var registry = HookRegistry.empty();
    assertThrows(NullPointerException.class, () -> registry.fireOnUserMessage(null, ctx()));
  }

  @Test
  void fireOnUserMessageRejectsNullCtx() {
    var registry = HookRegistry.empty();
    assertThrows(
        NullPointerException.class, () -> registry.fireOnUserMessage(UserMessage.text("x"), null));
  }

  @Test
  void fireOnStreamEventRejectsNullEvent() {
    var registry = HookRegistry.empty();
    assertThrows(NullPointerException.class, () -> registry.fireOnStreamEvent(null, ctx()));
  }

  @Test
  void fireOnStreamEventRejectsNullCtx() {
    var registry = HookRegistry.empty();
    var event = new QueryEvent.AssistantText("sess", 0, Instant.now(), "x");
    assertThrows(NullPointerException.class, () -> registry.fireOnStreamEvent(event, null));
  }

  // ── firing dispatch semantics ────────────────────────────────────────────

  @Test
  void firstDecisiveOutcomeWins() {
    var fired = new ArrayList<String>();
    PreToolUseHook continueHook =
        new PreToolUseHook() {
          @Override
          public HookOutcome.PreToolUse beforeTool(ToolCall call, HookContext ctx) {
            fired.add("continue");
            return HookOutcome.cont();
          }

          @Override
          public int priority() {
            return 10;
          }
        };
    PreToolUseHook blockHook =
        new PreToolUseHook() {
          @Override
          public HookOutcome.PreToolUse beforeTool(ToolCall call, HookContext ctx) {
            fired.add("block");
            return HookOutcome.block("nope");
          }

          @Override
          public int priority() {
            return 20;
          }
        };
    PreToolUseHook neverFiresHook =
        new PreToolUseHook() {
          @Override
          public HookOutcome.PreToolUse beforeTool(ToolCall call, HookContext ctx) {
            fired.add("after-block");
            return HookOutcome.cont();
          }

          @Override
          public int priority() {
            return 30;
          }
        };
    var registry = registry(List.of(neverFiresHook, blockHook, continueHook));
    var decision = registry.firePreToolUse(CALL, ctx()).decision();
    assertInstanceOf(HookOutcome.Block.class, decision.outcome());
    assertEquals(List.of("continue", "block"), fired);
    assertEquals(blockHook.name(), decision.firingHookName());
  }

  @Test
  void priorityOrdersAcrossPhase() {
    var fired = new ArrayList<String>();
    PreToolUseHook later =
        new PreToolUseHook() {
          @Override
          public HookOutcome.PreToolUse beforeTool(ToolCall call, HookContext ctx) {
            fired.add("later");
            return HookOutcome.cont();
          }

          @Override
          public int priority() {
            return 200;
          }
        };
    PreToolUseHook earlier =
        new PreToolUseHook() {
          @Override
          public HookOutcome.PreToolUse beforeTool(ToolCall call, HookContext ctx) {
            fired.add("earlier");
            return HookOutcome.cont();
          }

          @Override
          public int priority() {
            return 50;
          }
        };
    registry(List.of(later, earlier)).firePreToolUse(CALL, ctx());
    assertEquals(List.of("earlier", "later"), fired);
  }

  @Test
  void allObserveHooksFireRegardlessOfThrows() {
    var fired = new ArrayList<String>();
    OnStreamEventHook good = (event, ctx) -> fired.add("good");
    OnStreamEventHook bad =
        (event, ctx) -> {
          fired.add("bad");
          throw new RuntimeException("boom");
        };
    OnStreamEventHook other = (event, ctx) -> fired.add("other");
    registry(List.of(good, bad, other))
        .fireOnStreamEvent(new QueryEvent.AssistantText("s", 1, Instant.now(), "x"), ctx());
    assertEquals(List.of("good", "bad", "other"), fired);
  }

  @Test
  void aPreToolUseHookThatThrowsBlocksTheCallAndEndsTheChain() {
    var fired = new ArrayList<String>();
    PreToolUseHook throwing =
        named(
            "Guard",
            (call, ctx) -> {
              throw new IllegalStateException("hook boom");
            });
    PreToolUseHook later = (call, ctx) -> fired.add("later") ? HookOutcome.cont() : null;

    var result = registry(List.of(throwing, later)).firePreToolUse(CALL, ctx());

    assertEquals(
        HookDecision.of(
            "Guard", HookOutcome.block("hook threw java.lang.IllegalStateException: hook boom")),
        result.decision());
    assertEquals(List.of(), fired);
  }

  @Test
  void aPreToolUseHookReturningNullBlocksTheCallAndEndsTheChain() {
    var fired = new ArrayList<String>();
    PreToolUseHook nullReturn = named("Guard", (call, ctx) -> null);
    PreToolUseHook later = (call, ctx) -> fired.add("later") ? HookOutcome.cont() : null;

    var result = registry(List.of(nullReturn, later)).firePreToolUse(CALL, ctx());

    assertEquals(
        HookDecision.of("Guard", HookOutcome.block("hook returned null")), result.decision());
    assertEquals(List.of(), fired);
  }

  @Test
  void anErrorFromAPreToolUseHookEscapes() {
    var failure = new AssertionError("host failure");
    PreToolUseHook failing =
        (call, ctx) -> {
          throw failure;
        };
    var registry = registry(List.of(failing));
    assertSame(
        failure, assertThrows(AssertionError.class, () -> registry.firePreToolUse(CALL, ctx())));
  }

  @Test
  void eachPreToolUseHookSeesTheArgumentsTheHooksBeforeItLeft() {
    var seen = new ArrayList<Map<String, Object>>();
    PreToolUseHook a = named("A", (call, ctx) -> HookOutcome.mutateArgs(Map.of("path", "a")));
    PreToolUseHook b =
        named(
            "B",
            (call, ctx) -> {
              seen.add(call.arguments());
              return HookOutcome.mutateArgs(Map.of("path", "b"));
            });
    PreToolUseHook c =
        named(
            "C",
            (call, ctx) -> {
              seen.add(call.arguments());
              return HookOutcome.cont();
            });

    var result = registry(List.of(a, b, c)).firePreToolUse(CALL, ctx());

    assertEquals(List.of(Map.of("path", "a"), Map.of("path", "b")), seen);
    assertEquals(
        new PreToolUseResult(
            new ToolCall("c", "read", Map.of("path", "b")),
            List.of(
                HookDecision.of("A", HookOutcome.mutateArgs(Map.of("path", "a"))),
                HookDecision.of("B", HookOutcome.mutateArgs(Map.of("path", "b")))),
            new HookDecision<>(null, HookOutcome.cont())),
        result);
  }

  @Test
  void aBlockAfterAMutationEndsTheChainWithTheMutatedCall() {
    PreToolUseHook a = named("A", (call, ctx) -> HookOutcome.mutateArgs(Map.of("path", "a")));
    PreToolUseHook b = named("B", (call, ctx) -> HookOutcome.block("no"));

    var result = registry(List.of(a, b)).firePreToolUse(CALL, ctx());

    assertEquals(new ToolCall("c", "read", Map.of("path", "a")), result.call());
    assertEquals(HookDecision.of("B", HookOutcome.block("no")), result.decision());
  }

  // ── happy-path per phase ─────────────────────────────────────────────────

  @Test
  void firePostToolUseDispatches() {
    PostToolUseHook hook = (call, result, ctx) -> HookOutcome.inject("more");
    var decision = registry(List.of(hook)).firePostToolUse(CALL, OK, ctx());
    assertInstanceOf(HookOutcome.Inject.class, decision.outcome());
    assertEquals(hook.name(), decision.firingHookName());
  }

  @Test
  void firePreModelTurnDispatches() {
    PreModelTurnHook hook = (history, ctx) -> HookOutcome.stop("stopped");
    var decision = registry(List.of(hook)).firePreModelTurn(List.of(Message.user("hi")), ctx());
    assertInstanceOf(HookOutcome.Stop.class, decision.outcome());
    assertEquals(hook.name(), decision.firingHookName());
  }

  @Test
  void firePostModelTurnDispatches() {
    PostModelTurnHook hook = (response, ctx) -> HookOutcome.inject("retry");
    var resp = Response.newBuilder().withContent("x").withFinishReason(FinishReason.STOP).build();
    var decision = registry(List.of(hook)).firePostModelTurn(resp, ctx());
    assertInstanceOf(HookOutcome.Inject.class, decision.outcome());
  }

  @Test
  void firePreStopDispatches() {
    PreStopHook hook = (response, ctx) -> HookOutcome.inject("not yet");
    var resp = Response.newBuilder().withContent("x").withFinishReason(FinishReason.STOP).build();
    var decision = registry(List.of(hook)).firePreStop(resp, ctx());
    assertInstanceOf(HookOutcome.Inject.class, decision.outcome());
  }

  @Test
  void fireOnUserMessageDispatches() {
    OnUserMessageHook hook = (msg, ctx) -> HookOutcome.block("PII");
    var decision = registry(List.of(hook)).fireOnUserMessage(UserMessage.text("hi"), ctx());
    assertInstanceOf(HookOutcome.Block.class, decision.outcome());
    assertEquals(hook.name(), decision.firingHookName());
  }

  // ── log lines carry the name read when the registry was built ────────────

  /** A name that is {@code Gate} the first time it is read and {@code Renamed} after that. */
  private static Supplier<String> gateThenRenamed() {
    var reads = new AtomicInteger();
    return () -> reads.getAndIncrement() == 0 ? "Gate" : "Renamed";
  }

  private static LogRecord onlyWarning(Runnable fire) {
    var logged = new ArrayList<LogRecord>();
    try (var ignored = LogCapture.of(HookRegistry.class, logged)) {
      fire.run();
    }
    assertEquals(1, logged.size(), "log records");
    var only = logged.getFirst();
    assertEquals(Level.WARNING, only.getLevel());
    return only;
  }

  @Test
  void aThrowingDecisiveHookIsLoggedUnderItsRecordedName() {
    var failure = new IllegalStateException("boom");
    var name = gateThenRenamed();
    PreToolUseHook hook =
        new PreToolUseHook() {
          @Override
          public HookOutcome.PreToolUse beforeTool(ToolCall call, HookContext ctx) {
            throw failure;
          }

          @Override
          public String name() {
            return name.get();
          }
        };
    var registry = registry(List.of(hook));

    var logged = onlyWarning(() -> registry.firePreToolUse(CALL, ctx()));

    assertEquals(
        "PreToolUse hook Gate threw java.lang.IllegalStateException; refusing the call",
        logged.getMessage());
    RedactedLogs.assertCopyOf(failure, logged.getThrown());
  }

  @Test
  void aThrowingObserverPhaseHookIsLoggedUnderItsRecordedName() {
    var failure = new IllegalStateException("boom");
    var name = gateThenRenamed();
    PostToolUseHook hook =
        new PostToolUseHook() {
          @Override
          public HookOutcome.PostToolUse afterTool(
              ToolCall call, ToolResult result, HookContext ctx) {
            throw failure;
          }

          @Override
          public String name() {
            return name.get();
          }
        };
    var registry = registry(List.of(hook));

    var logged = onlyWarning(() -> registry.firePostToolUse(CALL, OK, ctx()));

    assertEquals("hook Gate threw; treating as Continue", logged.getMessage());
    RedactedLogs.assertCopyOf(failure, logged.getThrown());
  }

  @Test
  void aNullObserverPhaseOutcomeIsLoggedUnderItsRecordedName() {
    var name = gateThenRenamed();
    PostToolUseHook hook =
        new PostToolUseHook() {
          @Override
          public HookOutcome.PostToolUse afterTool(
              ToolCall call, ToolResult result, HookContext ctx) {
            return null;
          }

          @Override
          public String name() {
            return name.get();
          }
        };
    var registry = registry(List.of(hook));

    var logged = onlyWarning(() -> registry.firePostToolUse(CALL, OK, ctx()));

    assertEquals("hook Gate returned null outcome; treating as Continue", logged.getMessage());
  }

  @Test
  void aNullOutcomeIsLoggedUnderItsRecordedName() {
    var name = gateThenRenamed();
    PreToolUseHook hook =
        new PreToolUseHook() {
          @Override
          public HookOutcome.PreToolUse beforeTool(ToolCall call, HookContext ctx) {
            return null;
          }

          @Override
          public String name() {
            return name.get();
          }
        };
    var registry = registry(List.of(hook));

    var logged = onlyWarning(() -> registry.firePreToolUse(CALL, ctx()));

    assertEquals("PreToolUse hook Gate returned null; refusing the call", logged.getMessage());
  }

  @Test
  void aThrowingStreamEventHookIsLoggedUnderItsRecordedName() {
    var failure = new IllegalStateException("boom");
    var name = gateThenRenamed();
    OnStreamEventHook hook =
        new OnStreamEventHook() {
          @Override
          public void onEvent(QueryEvent event, HookContext ctx) {
            throw failure;
          }

          @Override
          public String name() {
            return name.get();
          }
        };
    var registry = registry(List.of(hook));
    var event = new QueryEvent.AssistantText("sess", 0, Instant.EPOCH, "x");

    var logged = onlyWarning(() -> registry.fireOnStreamEvent(event, ctx()));

    assertEquals("hook Gate threw on stream event", logged.getMessage());
    RedactedLogs.assertCopyOf(failure, logged.getThrown());
  }
}
