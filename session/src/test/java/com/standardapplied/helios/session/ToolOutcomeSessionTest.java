/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.model.InlineFile;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Role;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolExecutor;
import com.standardapplied.helios.core.tool.ToolOutcome;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.hooks.HookContext;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.PostToolUseHook;
import com.standardapplied.helios.session.hooks.PreToolUseHook;
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.permissions.PermissionEffect;
import com.standardapplied.helios.session.permissions.PermissionMode;
import com.standardapplied.helios.session.permissions.PermissionRule;
import com.standardapplied.helios.session.test.CollectingSubscriber;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolRegistry;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/**
 * Every path that produces a tool result, run through a session: each {@link QueryEvent.ToolResult}
 * carries the {@link ToolOutcome} of what happened and the exact text the model reads about it.
 */
final class ToolOutcomeSessionTest {

  private static final String SECRET = "sk-test-0123456789abcdef";

  private static final List<InlineFile> PNG =
      List.of(InlineFile.of(new byte[] {(byte) 0x89, 'P', 'N', 'G'}, "image/png"));

  /** A named {@code PreToolUse} hook, so the rejecting hook's name is stable. */
  private record Gate(HookOutcome outcome) implements PreToolUseHook {

    @Override
    public HookOutcome beforeTool(ToolCall call, HookContext ctx) {
      return outcome;
    }
  }

  private record Run(CollectingSubscriber events, ScriptedModel model) {

    List<QueryEvent.ToolResult> toolResults() {
      return events.eventsOf(QueryEvent.ToolResult.class);
    }

    ToolResult onlyResult() {
      var results = toolResults();
      assertEquals(1, results.size(), "tool results");
      return results.getFirst().result();
    }

    List<String> toolMessages(int call) {
      return model.calls().get(call).stream()
          .filter(m -> m.role() == Role.TOOL)
          .map(Message::content)
          .toList();
    }
  }

  private static ToolBinding tool(String name, ToolExecutor executor) {
    return ToolBinding.newBuilder(
            Tool.newBuilder().withName(name).withDescription(name).withExecutor(executor).build())
        .withCategory(ToolCategory.READ)
        .build();
  }

  private static ToolCall call(String name) {
    return new ToolCall("c-" + name, name, Map.of());
  }

  private static SessionOptions.Builder withTools(ToolBinding... tools) {
    return SessionOptions.newBuilder().withTools(new ToolRegistry(List.of(tools)));
  }

  private static Run run(SessionOptions.Builder options, ToolCall... calls) {
    var model = ScriptedModel.newBuilder().withToolCallsTurn(calls).withTextTurn("done").build();
    var events = new CollectingSubscriber();
    try (var session = AgentSession.create(options.withModel(model).build())) {
      session.events().subscribe(events);
      session.runBlocking(UserMessage.text("go"));
      events.awaitDone();
      return new Run(events, model);
    }
  }

  private static ToolResult resultOf(ToolExecutor executor) {
    return run(withTools(tool("t", executor)), call("t")).onlyResult();
  }

  private static ToolResult expected(ToolOutcome outcome, String output) {
    return new ToolResult(outcome, output, List.of());
  }

  @Test
  void aSuccessIsSucceeded() {
    assertEquals(
        expected(new ToolOutcome.Succeeded(), "ok"),
        resultOf((args, ctx) -> ToolResult.success("ok")));
  }

  @Test
  void aReportedFailureIsFailed() {
    assertEquals(
        expected(new ToolOutcome.Failed(), "nope"),
        resultOf((args, ctx) -> ToolResult.failure("nope")));
  }

  @Test
  void aThrownExceptionIsThrewWithItsType() {
    assertEquals(
        expected(
            new ToolOutcome.Threw("java.lang.IllegalStateException"), "tool 't' threw: bad state"),
        resultOf(
            (args, ctx) -> {
              throw new IllegalStateException("bad state");
            }));
  }

  @Test
  void aThrownErrorIsThrewWithItsType() {
    assertEquals(
        expected(new ToolOutcome.Threw("java.lang.AssertionError"), "tool 't' threw: broken"),
        resultOf(
            (args, ctx) -> {
              throw new AssertionError("broken");
            }));
  }

  @Test
  void aTimeoutIsTimedOutWithTheLimitInForceForTheCall() {
    var limit = Duration.ofMillis(50);
    var options =
        withTools(tool("hangs", (args, ctx) -> blockUntilInterrupted()))
            .withLimits(SessionLimits.newBuilder().withToolTimeoutDefault(limit).build());

    assertEquals(
        expected(new ToolOutcome.TimedOut(limit), "tool 'hangs' timed out after PT0.05S"),
        run(options, call("hangs")).onlyResult());
  }

  @Test
  void anUnknownToolIsNotFound() {
    assertEquals(
        expected(new ToolOutcome.NotFound(), "tool not found: missing"),
        run(withTools(), call("missing")).onlyResult());
  }

  @Test
  void aHookBlockIsRejectedByThatHook() {
    var options =
        withTools(tool("t", (args, ctx) -> ToolResult.success("ran")))
            .withHook(new Gate(HookOutcome.block("not allowed")));

    assertEquals(
        expected(new ToolOutcome.Rejected("Gate", "not allowed"), "blocked by Gate: not allowed"),
        run(options, call("t")).onlyResult());
  }

  @Test
  void aHookInjectIsRejectedByThatHook() {
    var options =
        withTools(tool("t", (args, ctx) -> ToolResult.success("ran")))
            .withHook(new Gate(HookOutcome.inject("look elsewhere")));

    assertEquals(
        expected(
            new ToolOutcome.Rejected("Gate", "hook injected a user message"),
            "blocked by Gate: hook injected a user message"),
        run(options, call("t")).onlyResult());
  }

  @Test
  void aPermissionDenialIsRejectedByTheEvaluator() {
    var options =
        withTools(tool("t", (args, ctx) -> ToolResult.success("ran")))
            .withPermission(
                new Permission(
                    PermissionMode.DEFAULT,
                    List.of(),
                    List.of(),
                    List.of(PermissionRule.any(PermissionEffect.DENY, "t"))));

    assertEquals(
        expected(
            new ToolOutcome.Rejected("DefaultPermissionEvaluator", "permission: deny rule for t"),
            "blocked by DefaultPermissionEvaluator: permission: deny rule for t"),
        run(options, call("t")).onlyResult());
  }

  @Test
  void aMutatedResultKeepsItsOutcomeAndAttachments() {
    var options =
        withTools(
                tool(
                    "t",
                    (args, ctx) -> new ToolResult(new ToolOutcome.Failed(), "raw failure", PNG)))
            .withHook(
                (PostToolUseHook) (toolCall, result, ctx) -> HookOutcome.mutateResult("rewritten"));

    var run = run(options, call("t"));

    assertEquals(
        List.of(
            new ToolResult(new ToolOutcome.Failed(), "raw failure", PNG),
            new ToolResult(new ToolOutcome.Failed(), "rewritten", PNG)),
        run.toolResults().stream().map(QueryEvent.ToolResult::result).toList());
    assertEquals(List.of("rewritten"), run.toolMessages(1));
  }

  @Test
  void aRejectedResultIsRedactedWithItsOutcomeIntact() {
    var secrets = new SecretRegistry();
    secrets.register("API_KEY", SECRET);
    var options =
        withTools(tool("t", (args, ctx) -> ToolResult.success("ran")))
            .withSecretRegistry(secrets)
            .withHook(new Gate(HookOutcome.block("saw " + SECRET)));

    var event = run(options, call("t")).toolResults().getFirst();

    assertEquals(
        expected(
            new ToolOutcome.Rejected("Gate", "saw " + SECRET),
            "blocked by Gate: saw <redacted:API_KEY>"),
        event.result());
    assertEquals(Map.of("API_KEY", 1), event.redactions());
  }

  @Test
  void aCallPendingWhenTheSessionClosesIsThrewCancellation() {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var options =
        withTools(
            tool(
                "holds",
                (args, ctx) -> {
                  entered.countDown();
                  return awaiting(release, ToolResult.success("held"));
                }),
            tool("next", (args, ctx) -> ToolResult.success("ran")));
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(call("holds"), call("next"))
            .withTextTurn("done")
            .build();
    var events = new CollectingSubscriber();
    try (var session = AgentSession.create(options.withModel(model).build())) {
      session.events().subscribe(events);
      session.send(UserMessage.text("go"));
      Await.latch("the first call to hold its permit", entered);

      session.close();
      release.countDown();

      assertInstanceOf(
          ResultMessage.Cancelled.class, Await.value("the session to end", session.result()));
      events.awaitDone();
    }
    assertEquals(
        List.of(
            expected(new ToolOutcome.Succeeded(), "held"),
            expected(
                new ToolOutcome.Threw("java.util.concurrent.CancellationException"),
                "tool 'next' threw: session closed")),
        events.eventsOf(QueryEvent.ToolResult.class).stream()
            .map(QueryEvent.ToolResult::result)
            .toList());
  }

  private static ToolResult blockUntilInterrupted() {
    return awaiting(new CountDownLatch(1), ToolResult.success("unreachable"));
  }

  private static ToolResult awaiting(CountDownLatch latch, ToolResult result) {
    try {
      latch.await();
      return result;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return ToolResult.failure("interrupted");
    }
  }
}
