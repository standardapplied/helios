/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.session.QueryEvent;
import ai.singlr.session.UserMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class HookRegistryTest {

  private static final ToolCall CALL = new ToolCall("c", "read", Map.of());
  private static final ToolResult OK = ToolResult.success("done");

  private static HookContext ctx() {
    return new HookContext() {
      @Override
      public String sessionId() {
        return "sess";
      }

      @Override
      public long turnIndex() {
        return 0;
      }

      @Override
      public CancellationToken cancellation() {
        return new CancellationToken();
      }

      @Override
      public Model model() {
        return new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            return Response.newBuilder().build();
          }

          @Override
          public String id() {
            return "stub";
          }

          @Override
          public String provider() {
            return "stub";
          }
        };
      }
    };
  }

  // ── construction ──────────────────────────────────────────────────────────

  @Test
  void constructorRejectsNullList() {
    var ex = assertThrows(NullPointerException.class, () -> new HookRegistry(null));
    assertEquals("hooks must not be null", ex.getMessage());
  }

  @Test
  void constructorRejectsListContainingNull() {
    var list = new ArrayList<Hook>();
    list.add(null);
    var ex = assertThrows(NullPointerException.class, () -> new HookRegistry(list));
    assertEquals("hooks must not contain null", ex.getMessage());
  }

  @Test
  void emptyFactoryProducesEmptyRegistry() {
    var registry = HookRegistry.empty();
    assertEquals(0, registry.countOf(PreToolUseHook.class));
    assertEquals(0, registry.countOf(OnStreamEventHook.class));
  }

  @Test
  void countOfPartitionsByPhase() {
    PreToolUseHook a = (call, ctx) -> HookOutcome.cont();
    PreToolUseHook b = (call, ctx) -> HookOutcome.cont();
    PostToolUseHook c = (call, result, ctx) -> HookOutcome.cont();
    var registry = new HookRegistry(List.of(a, b, c));
    assertEquals(2, registry.countOf(PreToolUseHook.class));
    assertEquals(1, registry.countOf(PostToolUseHook.class));
    assertEquals(0, registry.countOf(PreModelTurnHook.class));
    assertEquals(0, registry.countOf(PostModelTurnHook.class));
    assertEquals(0, registry.countOf(PreStopHook.class));
    assertEquals(0, registry.countOf(OnUserMessageHook.class));
    assertEquals(0, registry.countOf(OnStreamEventHook.class));
  }

  @Test
  void countOfUnknownPhaseReturnsZero() {
    var registry = HookRegistry.empty();
    assertEquals(0, registry.countOf(Hook.class));
  }

  // ── empty registry returns Continue for every phase ──────────────────────

  @Test
  void firePreToolUseEmptyReturnsContinue() {
    var decision = HookRegistry.empty().firePreToolUse(CALL, ctx());
    assertTrue(decision.shouldContinue());
    assertInstanceOf(HookOutcome.Continue.class, decision.outcome());
    assertTrue(decision.firingHookOptional().isEmpty());
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
          public HookOutcome beforeTool(ToolCall call, HookContext ctx) {
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
          public HookOutcome beforeTool(ToolCall call, HookContext ctx) {
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
          public HookOutcome beforeTool(ToolCall call, HookContext ctx) {
            fired.add("after-block");
            return HookOutcome.cont();
          }

          @Override
          public int priority() {
            return 30;
          }
        };
    var registry = new HookRegistry(List.of(neverFiresHook, blockHook, continueHook));
    var decision = registry.firePreToolUse(CALL, ctx());
    assertInstanceOf(HookOutcome.Block.class, decision.outcome());
    assertEquals(List.of("continue", "block"), fired);
    assertEquals(blockHook, decision.firingHookOptional().orElseThrow());
  }

  @Test
  void priorityOrdersAcrossPhase() {
    var fired = new ArrayList<String>();
    PreToolUseHook later =
        new PreToolUseHook() {
          @Override
          public HookOutcome beforeTool(ToolCall call, HookContext ctx) {
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
          public HookOutcome beforeTool(ToolCall call, HookContext ctx) {
            fired.add("earlier");
            return HookOutcome.cont();
          }

          @Override
          public int priority() {
            return 50;
          }
        };
    new HookRegistry(List.of(later, earlier)).firePreToolUse(CALL, ctx());
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
    new HookRegistry(List.of(good, bad, other))
        .fireOnStreamEvent(new QueryEvent.AssistantText("s", 1, Instant.now(), "x"), ctx());
    assertEquals(List.of("good", "bad", "other"), fired);
  }

  @Test
  void hookThrowsTreatedAsContinue() {
    PreToolUseHook throwing =
        (call, ctx) -> {
          throw new RuntimeException("hook boom");
        };
    PreToolUseHook decisive = (call, ctx) -> HookOutcome.block("nope");
    var fired = new ArrayList<String>();
    PreToolUseHook tracking =
        new PreToolUseHook() {
          @Override
          public HookOutcome beforeTool(ToolCall call, HookContext ctx) {
            fired.add("tracked");
            return HookOutcome.cont();
          }
        };
    var decision =
        new HookRegistry(List.of(throwing, tracking, decisive)).firePreToolUse(CALL, ctx());
    assertTrue(fired.contains("tracked"), "subsequent hooks fired after throw");
    assertInstanceOf(HookOutcome.Block.class, decision.outcome());
  }

  @Test
  void hookReturningNullTreatedAsContinue() {
    PreToolUseHook nullReturn = (call, ctx) -> null;
    PreToolUseHook decisive = (call, ctx) -> HookOutcome.block("nope");
    var decision = new HookRegistry(List.of(nullReturn, decisive)).firePreToolUse(CALL, ctx());
    assertInstanceOf(HookOutcome.Block.class, decision.outcome());
  }

  // ── happy-path per phase ─────────────────────────────────────────────────

  @Test
  void firePostToolUseDispatches() {
    PostToolUseHook hook = (call, result, ctx) -> HookOutcome.inject("more");
    var decision = new HookRegistry(List.of(hook)).firePostToolUse(CALL, OK, ctx());
    assertInstanceOf(HookOutcome.Inject.class, decision.outcome());
    assertEquals(hook, decision.firingHookOptional().orElseThrow());
  }

  @Test
  void firePreModelTurnDispatches() {
    PreModelTurnHook hook = (history, ctx) -> HookOutcome.stop("stopped");
    var decision =
        new HookRegistry(List.of(hook)).firePreModelTurn(List.of(Message.user("hi")), ctx());
    assertInstanceOf(HookOutcome.Stop.class, decision.outcome());
    assertEquals(hook, decision.firingHookOptional().orElseThrow());
  }

  @Test
  void firePostModelTurnDispatches() {
    PostModelTurnHook hook = (response, ctx) -> HookOutcome.inject("retry");
    var resp = Response.newBuilder().withContent("x").withFinishReason(FinishReason.STOP).build();
    var decision = new HookRegistry(List.of(hook)).firePostModelTurn(resp, ctx());
    assertInstanceOf(HookOutcome.Inject.class, decision.outcome());
  }

  @Test
  void firePreStopDispatches() {
    PreStopHook hook = (response, ctx) -> HookOutcome.inject("not yet");
    var resp = Response.newBuilder().withContent("x").withFinishReason(FinishReason.STOP).build();
    var decision = new HookRegistry(List.of(hook)).firePreStop(resp, ctx());
    assertInstanceOf(HookOutcome.Inject.class, decision.outcome());
  }

  @Test
  void fireOnUserMessageDispatches() {
    OnUserMessageHook hook = (msg, ctx) -> HookOutcome.block("PII");
    var decision = new HookRegistry(List.of(hook)).fireOnUserMessage(UserMessage.text("hi"), ctx());
    assertInstanceOf(HookOutcome.Block.class, decision.outcome());
    assertEquals(hook, decision.firingHookOptional().orElseThrow());
  }
}
