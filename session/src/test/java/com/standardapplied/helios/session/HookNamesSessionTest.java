/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.session;

import static com.standardapplied.helios.session.EndingSessions.binding;
import static com.standardapplied.helios.session.test.HookInputs.call;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.ToolOutcome;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.hooks.Hook;
import com.standardapplied.helios.session.hooks.HookContext;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.PostToolUseHook;
import com.standardapplied.helios.session.hooks.PreToolUseHook;
import com.standardapplied.helios.session.test.EventCollector;
import com.standardapplied.helios.testing.ScriptedModel;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * A hook's name, run through a session: an anonymous hook decides under its binary class name, a
 * hook whose name is null, blank or throws stops {@link AgentSession#create}, and the name a
 * session reports is the one read when it was built.
 */
final class HookNamesSessionTest {

  private static final String FIRST_ANONYMOUS = HookNamesSessionTest.class.getName() + "$1";
  private static final String SECOND_ANONYMOUS = HookNamesSessionTest.class.getName() + "$2";

  /** A {@code PreToolUse} hook that blocks every call under the name {@code source} gives. */
  private record NamedBy(Supplier<String> source) implements PreToolUseHook {

    @Override
    public HookOutcome.PreToolUse beforeTool(ToolCall call, HookContext ctx) {
      return HookOutcome.block("no");
    }

    @Override
    public String name() {
      return source.get();
    }
  }

  private record Run(EventCollector events, ResultMessage terminal) {

    ToolResult onlyResult() {
      var results = events.eventsOf(QueryEvent.ToolResult.class);
      assertEquals(1, results.size(), "tool results");
      return results.getFirst().result();
    }

    QueryEvent.HookFired onlyHookFired() {
      var fired = events.eventsOf(QueryEvent.HookFired.class);
      assertEquals(1, fired.size(), "hook fired events");
      return fired.getFirst();
    }

    QueryEvent.ToolBlocked onlyToolBlocked() {
      var blocked = events.eventsOf(QueryEvent.ToolBlocked.class);
      assertEquals(1, blocked.size(), "tool blocked events");
      return blocked.getFirst();
    }
  }

  private static SessionOptions options(Hook hook) {
    var model =
        ScriptedModel.newBuilder().withToolCallsTurn(call("t")).withTextTurn("done").build();
    var tool = binding("t", (args, ctx) -> ToolResult.success("ran"));
    return SessionOptions.newBuilder()
        .withModel(model)
        .withTools(List.of(tool))
        .withHook(hook)
        .build();
  }

  private static Run run(Hook hook) {
    var events = new EventCollector();
    try (var session = AgentSession.open(options(hook))) {
      events.read(session.events());
      var future = session.send(UserMessage.text("go"));
      var terminal = Await.value("the session to end", future);
      events.awaitRunEnded();
      return new Run(events, terminal);
    }
  }

  @Test
  void anAnonymousBlockingHookIsNamedByItsBinaryName() {
    var run =
        run(
            new PreToolUseHook() {
              @Override
              public HookOutcome.PreToolUse beforeTool(ToolCall call, HookContext ctx) {
                return HookOutcome.block("no");
              }
            });

    assertInstanceOf(ResultMessage.Success.class, run.terminal());
    assertEquals(FIRST_ANONYMOUS, run.onlyToolBlocked().hookName());
    assertEquals(FIRST_ANONYMOUS, run.onlyHookFired().hookName());
    assertEquals(new ToolOutcome.Rejected(FIRST_ANONYMOUS, "no"), run.onlyResult().outcome());
  }

  @Test
  void anAnonymousResultMutatingHookIsNamedByItsBinaryName() {
    var run =
        run(
            new PostToolUseHook() {
              @Override
              public HookOutcome.PostToolUse afterTool(
                  ToolCall call, ToolResult result, HookContext ctx) {
                return HookOutcome.mutateResult("x");
              }
            });

    assertInstanceOf(ResultMessage.Success.class, run.terminal());
    var fired = run.onlyHookFired();
    assertEquals(SECOND_ANONYMOUS, fired.hookName());
    assertEquals("MutateResult", fired.outcomeKind());
  }

  @Test
  void aNullNameStopsTheSessionBeingCreated() {
    var options = options(new NamedBy(() -> null));

    var ex = assertThrows(NullPointerException.class, () -> AgentSession.open(options));

    assertEquals("hook " + NamedBy.class.getName() + " returned a null name", ex.getMessage());
  }

  @Test
  void aBlankNameStopsTheSessionBeingCreated() {
    var options = options(new NamedBy(() -> " \t"));

    var ex = assertThrows(IllegalArgumentException.class, () -> AgentSession.open(options));

    assertEquals("hook " + NamedBy.class.getName() + " returned a blank name", ex.getMessage());
  }

  @Test
  void theReservedNamePermissionStopsTheSessionBeingCreated() {
    var options = options(new NamedBy(() -> "Permission"));

    var ex = assertThrows(IllegalArgumentException.class, () -> AgentSession.open(options));

    assertEquals(
        "hook " + NamedBy.class.getName() + " uses the reserved name Permission", ex.getMessage());
  }

  @Test
  void aNameThatThrowsStopsTheSessionBeingCreatedWithThatException() {
    var failure = new IllegalStateException("no name");
    var options =
        options(
            new NamedBy(
                () -> {
                  throw failure;
                }));

    var ex = assertThrows(IllegalStateException.class, () -> AgentSession.open(options));

    assertSame(failure, ex);
  }

  @Test
  void theNameReadWhenTheSessionWasBuiltIsTheOneItReports() {
    var reads = new AtomicInteger();
    var run = run(new NamedBy(() -> reads.getAndIncrement() == 0 ? "Gate" : null));

    assertInstanceOf(ResultMessage.Success.class, run.terminal());
    assertEquals("Gate", run.onlyToolBlocked().hookName());
    assertEquals(new ToolOutcome.Rejected("Gate", "no"), run.onlyResult().outcome());
    assertEquals(1, reads.get());
  }
}
