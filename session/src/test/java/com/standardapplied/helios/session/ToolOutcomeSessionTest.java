/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.session;

import static com.standardapplied.helios.session.WriteToolSessions.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

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
import java.util.concurrent.atomic.AtomicInteger;
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
  private record Gate(HookOutcome.PreToolUse outcome) implements PreToolUseHook {

    @Override
    public HookOutcome.PreToolUse beforeTool(ToolCall call, HookContext ctx) {
      return outcome;
    }
  }

  private static List<QueryEvent.ToolResult> toolResults(WriteToolSessions.Run run) {
    return run.events().eventsOf(QueryEvent.ToolResult.class);
  }

  private static List<String> toolMessages(WriteToolSessions.Run run, int call) {
    return run.model().calls().get(call).stream()
        .filter(m -> m.role() == Role.TOOL)
        .map(Message::content)
        .toList();
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
  void aToolsErrorEndsTheSessionWithThatErrorAndRunsNothingAfterIt() {
    var sentinel = new AssertionError("broken");
    var laterCalls = new AtomicInteger();
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(call("t"), call("later"))
            .withTextTurn("never")
            .build();
    var options =
        withTools(
                tool(
                    "t",
                    (args, ctx) -> {
                      throw sentinel;
                    }),
                tool(
                    "later",
                    (args, ctx) -> {
                      laterCalls.incrementAndGet();
                      return ToolResult.success("ran");
                    }))
            .withModel(model)
            .build();

    try (var session = AgentSession.create(options)) {
      session.send(UserMessage.text("go"));

      assertSame(sentinel, Await.failure("the session to fail", session.result()));
    }
    assertEquals(1, model.calls().size());
    assertEquals(0, laterCalls.get());
  }

  @Test
  void anExecutorReturningNullIsThrewAndTheSessionGoesOn() {
    var run = run(withTools(tool("t", (args, ctx) -> null)), call("t"));

    var reported = "tool 't' threw: executor returned null";
    assertEquals(
        expected(new ToolOutcome.Threw("java.lang.NullPointerException"), reported),
        run.onlyResult());
    assertEquals(List.of(reported), toolMessages(run, 1));
    assertInstanceOf(ResultMessage.Success.class, run.terminal());
  }

  @Test
  void aTimeoutIsTimedOutWithTheLimitInForceForTheCall() {
    var limit = Duration.ofMillis(50);
    var options =
        withTools(tool("hangs", (args, ctx) -> blockUntilInterrupted()))
            .withLimits(
                SessionLimits.newBuilder()
                    .withToolTimeoutDefault(limit)
                    .withStopGrace(Duration.ofMinutes(10))
                    .build());

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
  void aPermissionDenialIsRejectedByThePermissionPolicy() {
    var options =
        withTools(tool("t", (args, ctx) -> ToolResult.success("ran")))
            .withPermission(
                new Permission(
                    PermissionMode.DEFAULT,
                    List.of(),
                    List.of(),
                    List.of(PermissionRule.any("t"))));

    assertEquals(
        expected(
            new ToolOutcome.Rejected("Permission", "deny rule t matched t"),
            "blocked by Permission: deny rule t matched t"),
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
        toolResults(run).stream().map(QueryEvent.ToolResult::result).toList());
    assertEquals(List.of("rewritten"), toolMessages(run, 1));
  }

  @Test
  void aRejectionReasonIsRedactedInItsOutcomeAndOutput() {
    var secrets = new SecretRegistry();
    secrets.register("API_KEY", SECRET);
    var options =
        withTools(tool("t", (args, ctx) -> ToolResult.success("ran")))
            .withSecretRegistry(secrets)
            .withHook(new Gate(HookOutcome.block("saw " + SECRET)));

    var run = run(options, call("t"));

    assertEquals(
        expected(
            new ToolOutcome.Rejected("Gate", "saw <redacted:API_KEY>"),
            "blocked by Gate: saw <redacted:API_KEY>"),
        run.onlyResult());
    assertEquals(
        "saw <redacted:API_KEY>",
        run.events().eventsOf(QueryEvent.ToolBlocked.class).getFirst().reason());
    assertEquals(Map.of("API_KEY", 1), toolResults(run).getFirst().redactions());
  }

  @Test
  void closingTheSessionCancelsTheRunningCallAndRefusesTheNext() {
    var entered = new CountDownLatch(1);
    var options =
        withTools(
            tool(
                "holds",
                (args, ctx) -> {
                  entered.countDown();
                  return blockUntilInterrupted();
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
      Await.latch("the first call to start", entered);

      session.close();

      assertInstanceOf(
          ResultMessage.Cancelled.class, Await.value("the session to end", session.result()));
      events.awaitDone();
    }
    assertEquals(
        List.of(
            expected(
                new ToolOutcome.Threw("java.util.concurrent.CancellationException"),
                "tool 'holds' threw: session closed"),
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
