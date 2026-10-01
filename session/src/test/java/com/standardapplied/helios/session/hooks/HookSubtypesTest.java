/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for the 7 phase-specific hook interfaces — accessors, default name(), default priority().
 */
final class HookSubtypesTest {

  private static final HookContext CTX = ctx();

  private static HookContext ctx() {
    return new HookContext() {
      @Override
      public String sessionId() {
        return "sess-1";
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

  // ── default name() / priority() across all 7 subtypes ──────────────────────

  @Test
  void preToolUseDefaultNameAndPriority() {
    PreToolUseHook hook = (call, ctx) -> HookOutcome.cont();
    assertEquals(100, hook.priority());
    assertEquals(hook.getClass().getSimpleName(), hook.name());
  }

  @Test
  void postToolUseDefaultNameAndPriority() {
    PostToolUseHook hook = (call, result, ctx) -> HookOutcome.cont();
    assertEquals(100, hook.priority());
    assertEquals(hook.getClass().getSimpleName(), hook.name());
  }

  @Test
  void preModelTurnDefaultNameAndPriority() {
    PreModelTurnHook hook = (history, ctx) -> HookOutcome.cont();
    assertEquals(100, hook.priority());
    assertEquals(hook.getClass().getSimpleName(), hook.name());
  }

  @Test
  void postModelTurnDefaultNameAndPriority() {
    PostModelTurnHook hook = (response, ctx) -> HookOutcome.cont();
    assertEquals(100, hook.priority());
    assertEquals(hook.getClass().getSimpleName(), hook.name());
  }

  @Test
  void preStopDefaultNameAndPriority() {
    PreStopHook hook = (response, ctx) -> HookOutcome.cont();
    assertEquals(100, hook.priority());
    assertEquals(hook.getClass().getSimpleName(), hook.name());
  }

  @Test
  void onUserMessageDefaultNameAndPriority() {
    OnUserMessageHook hook = (msg, ctx) -> HookOutcome.cont();
    assertEquals(100, hook.priority());
    assertEquals(hook.getClass().getSimpleName(), hook.name());
  }

  @Test
  void onStreamEventDefaultNameAndPriority() {
    OnStreamEventHook hook = (event, ctx) -> {};
    assertEquals(100, hook.priority());
    assertEquals(hook.getClass().getSimpleName(), hook.name());
  }

  // ── direct invocation smoke ───────────────────────────────────────────────

  @Test
  void preToolUseInvokes() {
    PreToolUseHook hook = (call, ctx) -> HookOutcome.block("nope");
    var call = new ToolCall("c", "read", Map.of());
    var outcome = hook.beforeTool(call, CTX);
    assertInstanceOf(HookOutcome.Block.class, outcome);
  }

  @Test
  void postToolUseInvokes() {
    PostToolUseHook hook = (call, result, ctx) -> HookOutcome.cont();
    var call = new ToolCall("c", "read", Map.of());
    var result = ToolResult.success("ok");
    assertInstanceOf(HookOutcome.Continue.class, hook.afterTool(call, result, CTX));
  }

  @Test
  void preModelTurnInvokes() {
    PreModelTurnHook hook = (history, ctx) -> HookOutcome.cont();
    assertInstanceOf(
        HookOutcome.Continue.class, hook.beforeModelTurn(List.of(Message.user("hi")), CTX));
  }

  @Test
  void postModelTurnInvokes() {
    PostModelTurnHook hook = (response, ctx) -> HookOutcome.inject("more");
    var resp = Response.newBuilder().withContent("x").withFinishReason(FinishReason.STOP).build();
    var outcome = hook.afterModelTurn(resp, CTX);
    assertInstanceOf(HookOutcome.Inject.class, outcome);
  }

  @Test
  void preStopInvokes() {
    PreStopHook hook = (response, ctx) -> HookOutcome.stop("override");
    var resp = Response.newBuilder().withContent("x").withFinishReason(FinishReason.STOP).build();
    var outcome = hook.beforeStop(resp, CTX);
    assertInstanceOf(HookOutcome.Stop.class, outcome);
  }

  @Test
  void onUserMessageInvokes() {
    OnUserMessageHook hook = (msg, ctx) -> HookOutcome.mutateText("rewritten");
    var outcome = hook.onUserMessage(UserMessage.text("hi"), CTX);
    var m = assertInstanceOf(HookOutcome.MutateText.class, outcome);
    assertEquals("rewritten", m.text());
  }

  @Test
  void onStreamEventInvokes() {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    OnStreamEventHook hook = (event, ctx) -> calls.incrementAndGet();
    hook.onEvent(new QueryEvent.AssistantText("sess", 1, Instant.now(), "x"), CTX);
    assertEquals(1, calls.get());
  }

  // ── custom name + priority overrides ──────────────────────────────────────

  @Test
  void customNameOverride() {
    PreToolUseHook hook =
        new PreToolUseHook() {
          @Override
          public HookOutcome beforeTool(ToolCall call, HookContext ctx) {
            return HookOutcome.cont();
          }

          @Override
          public String name() {
            return "MyHook";
          }
        };
    assertEquals("MyHook", hook.name());
  }

  @Test
  void customPriorityOverride() {
    PreToolUseHook hook =
        new PreToolUseHook() {
          @Override
          public HookOutcome beforeTool(ToolCall call, HookContext ctx) {
            return HookOutcome.cont();
          }

          @Override
          public int priority() {
            return 50;
          }
        };
    assertEquals(50, hook.priority());
  }

  // ── HookContext accessor smoke ────────────────────────────────────────────

  @Test
  void hookContextAccessorsExposeConstructorValues() {
    var t = new CancellationToken();
    Model m =
        new Model() {
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
    HookContext ctx =
        new HookContext() {
          @Override
          public String sessionId() {
            return "sess-2";
          }

          @Override
          public long turnIndex() {
            return 7;
          }

          @Override
          public CancellationToken cancellation() {
            return t;
          }

          @Override
          public Model model() {
            return m;
          }
        };
    assertEquals("sess-2", ctx.sessionId());
    assertEquals(7, ctx.turnIndex());
    assertEquals(t, ctx.cancellation());
    assertEquals(m, ctx.model());
  }
}
