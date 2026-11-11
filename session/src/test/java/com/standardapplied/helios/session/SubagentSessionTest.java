/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.CostCalculator;
import com.standardapplied.helios.core.common.CostCalculator.Pricing;
import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.model.TransientStreamException;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.Uninterruptible;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.execution.SessionStartOutcome;
import com.standardapplied.helios.session.hooks.HookContext;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.OnStreamEventHook;
import com.standardapplied.helios.session.hooks.PreToolUseHook;
import com.standardapplied.helios.session.loop.SessionState;
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.permissions.PermissionMode;
import com.standardapplied.helios.session.permissions.PermissionRule;
import com.standardapplied.helios.session.store.InMemorySessionStore;
import com.standardapplied.helios.session.store.SessionStoreException;
import com.standardapplied.helios.session.test.SpanCapture;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolPermissionKey;
import com.standardapplied.helios.testing.ModelStreams;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A session's model delegates to a {@link Subagent} through the {@code Agent} tool: the child runs
 * in the foreground of the call, its result is the call's, its spend the parent run's, its run span
 * nests under the call and names the parent, the call's deadline is the child's own, its
 * cancellation reaches the child, hooks and permissions gate it, and a child never asks.
 */
final class SubagentSessionTest {

  private static final String SID = "sess-parent";
  private static final Duration NOT_REACHED = EndingSessions.NOT_REACHED;
  private static final CostCalculator COSTS =
      CostCalculator.staticTable(
          Map.of(
              "parent",
              new Pricing(1_000_000, 1_000_000),
              "child",
              new Pricing(1_000_000, 1_000_000)));

  /** The child's typed result. */
  public record Match(String name, int score) {}

  private final SpanCapture capture = new SpanCapture();
  private final List<ScriptedModel> children = new ArrayList<>();

  private static ToolCall agent(String name, String prompt) {
    return new ToolCall("c-agent", AgentTool.NAME, Map.of("agent", name, "prompt", prompt));
  }

  private static ScriptedModel.Builder parentModel() {
    return ScriptedModel.newBuilder().withId("parent");
  }

  /** A parent that calls {@code matching} once with {@code usage}, then answers. */
  private static ScriptedModel callingMatching(Usage usage) {
    return parentModel()
        .withToolCallsTurn(usage, agent("matching", "find a match for bob"))
        .withTextTurn("answered")
        .build();
  }

  private ScriptedModel.Builder childModel() {
    return ScriptedModel.newBuilder().withId("child");
  }

  private SessionOptions.Builder child(ScriptedModel model) {
    children.add(model);
    return SessionOptions.newBuilder()
        .withModel(model)
        .withCostCalculator(COSTS)
        .withOpenTelemetry(capture.openTelemetry());
  }

  private SessionOptions.Builder parent(ScriptedModel model, SessionOptions.Builder child) {
    return SessionOptions.newBuilder()
        .withModel(model)
        .withSessionId(SID)
        .withCostCalculator(COSTS)
        .withOpenTelemetry(capture.openTelemetry())
        .withPermission(
            new Permission(
                PermissionMode.DEFAULT,
                List.of(PermissionRule.any(AgentTool.NAME)),
                List.of(),
                List.of()))
        .withSubagent(new Subagent("matching", "finds a match", child.build()));
  }

  private static ResultMessage run(SessionOptions options) {
    try (var session = AgentSession.open(options)) {
      return Await.value("the parent's result", session.send("find a match"));
    }
  }

  /** What the parent's model read as the {@code Agent} call's result, on its second call. */
  private static String agentResult(ScriptedModel parent) {
    return parent.calls().get(1).getLast().content();
  }

  private String childSpan(String key) {
    return capture.only("invoke_agent matching").getAttributes().get(stringKey(key));
  }

  private static ToolBinding answering(String name, String output) {
    return EndingSessions.binding(name, (arguments, context) -> ToolResult.success(output));
  }

  @Test
  void aChildsTypedResultIsTheCallsResultItsSpendIsTheParentRunsAndItsRunNestsUnderTheCall() {
    var childModel =
        childModel()
            .withToolCallsTurn(
                Usage.of(50, 5),
                new ToolCall("c-lookup", "lookup", Map.of()),
                new ToolCall("c-score", "score", Map.of()))
            .withTextTurn(
                "{\"name\":\"ada\",\"score\":9}",
                Usage.newBuilder()
                    .withInputTokens(60)
                    .withOutputTokens(6)
                    .withReasoningTokens(4)
                    .build())
            .build();
    var parentModel =
        parentModel()
            .withToolCallsTurn(Usage.of(100, 10), agent("matching", "find a match for bob"))
            .withTextTurn("ada", Usage.of(200, 20))
            .build();
    var store = new InMemorySessionStore();
    var childOptions =
        child(childModel)
            .withTools(List.of(answering("lookup", "ada"), answering("score", "9")))
            .withOutputSchema(OutputSchema.of(Match.class))
            .withStore(store);

    var result = run(parent(parentModel, childOptions).build());

    assertInstanceOf(ResultMessage.Success.class, result);
    assertEquals("{\"name\":\"ada\",\"score\":9}", agentResult(parentModel));
    assertEquals("find a match for bob", childModel.calls().getFirst().getLast().content());
    assertEquals(
        List.of("ada", "9"),
        childModel.calls().get(1).stream()
            .filter(message -> message.toolCallId() != null)
            .map(Message::content)
            .toList());
    assertEquals(new Usage(410, 41, 0, 0, 451, 200, 4), result.usage());
    assertEquals(CostEstimate.ofMicroUsd(410 + 41), result.cost());

    var parentRun = capture.only("invoke_agent helios");
    var call = capture.only("execute_tool Agent");
    var childRun = capture.only("invoke_agent matching");
    assertEquals(parentRun.getSpanId(), call.getParentSpanId());
    assertEquals(call.getTraceId(), childRun.getTraceId());
    assertEquals(call.getSpanId(), childRun.getParentSpanId());
    assertEquals(SID, childSpan("helios.parent.session_id"));
    assertEquals(result.runId(), childSpan("helios.parent.run_id"));
    assertEquals("c-agent", childSpan("helios.parent.tool_call_id"));
    assertNull(parentRun.getAttributes().get(stringKey("helios.parent.session_id")));

    var childId = childSpan("gen_ai.conversation.id");
    assertTrue(childId.startsWith("sess-"), childId);
    assertNotEquals(SID, childId);
    var stored = store.load(childId);
    assertEquals("Success", stored.lastRunEnded().orElseThrow().terminal());
    assertEquals(
        "find a match for bob", stored.history().getFirst().content(), "the child's own history");
  }

  @Test
  void aChildNeverSeesTheParentsSkills() {
    var childModel =
        childModel()
            .withToolCallsTurn(new ToolCall("c-skill", SkillTool.NAME, Map.of("skill", "pdf")))
            .withTextTurn("found")
            .build();
    var parentModel = callingMatching(Usage.of(1, 1));

    var result =
        run(
            parent(parentModel, child(childModel))
                .withSkill(
                    Skill.newBuilder()
                        .withName("pdf")
                        .withDescription("Extracts text from PDFs.")
                        .withInstructions("Use pdfplumber.")
                        .build())
                .build());

    assertInstanceOf(ResultMessage.Success.class, result);
    assertTrue(
        parentModel.calls().getFirst().getFirst().content().contains("<name>pdf</name>"),
        "the parent's catalog");
    assertEquals(List.of(Message.user("find a match for bob")), childModel.calls().getFirst());
    assertEquals(
        Message.tool("c-skill", SkillTool.NAME, ToolResult.notFound(SkillTool.NAME).output()),
        childModel.calls().get(1).getLast());
  }

  @Test
  void aChildLoadsTheSkillsOfItsOwnOptions() {
    var childModel =
        childModel()
            .withToolCallsTurn(
                new ToolCall("c-skill", SkillTool.NAME, Map.of("skill", "matching-rules")))
            .withTextTurn("found")
            .build();
    var parentModel = callingMatching(Usage.of(1, 1));
    var rules =
        Skill.newBuilder()
            .withName("matching-rules")
            .withDescription("How to match members.")
            .withInstructions("Prefer shared hobbies.")
            .build();

    var result = run(parent(parentModel, child(childModel).withSkill(rules)).build());

    assertInstanceOf(ResultMessage.Success.class, result);
    assertEquals("found", agentResult(parentModel));
    assertEquals(List.of(Message.user("find a match")), parentModel.calls().getFirst());
    assertTrue(
        childModel.calls().getFirst().getFirst().content().contains("<name>matching-rules</name>"),
        "the child's catalog");
    assertEquals(
        Message.tool(
            "c-skill",
            SkillTool.NAME,
            "<skill_content name=\"matching-rules\">\nPrefer shared hobbies.\n</skill_content>"),
        childModel.calls().get(1).getLast());
  }

  @Test
  void theUsageAChildsCutCallReportedIsTheParentRunsSpend() {
    var child =
        child(
            childModel()
                .withStreamTurn(
                    ModelStreams.failing(
                        new IllegalStateException("connection reset"),
                        new ModelChunk.UsageDelta(Usage.of(50, 5)),
                        new ModelChunk.TextDelta("half")))
                .build());

    var result = run(parent(callingMatching(Usage.of(100, 10)), child).build());

    assertInstanceOf(ResultMessage.Success.class, result);
    assertEquals(new Usage(150, 15, 0, 0, 165, 100, 0), result.usage());
    assertEquals(CostEstimate.ofMicroUsd(110 + 55), result.cost());
  }

  @Test
  void aCallWithNoBudgetLeftFailsWithoutStartingAChild() {
    var parentModel = callingMatching(Usage.of(100, 20));
    var options =
        parent(parentModel, child(childModel().withTextTurn("found").build()))
            .withLimits(SessionLimits.newBuilder().withMaxBudgetMicroUsd(120).build())
            .build();

    var result = run(options);

    assertInstanceOf(ResultMessage.Success.class, result);
    assertEquals(
        "Agent: subagent \"matching\" did not start: the run has spent 120 of its 120 micro-USD"
            + " budget",
        agentResult(parentModel));
    assertEquals(List.of(), children.getFirst().calls());
    assertEquals(List.of(), capture.named("invoke_agent matching"));
  }

  @Test
  void aChildThatSpendsPastWhatTheParentHasLeftEndsOverBudgetAndSoDoesTheParent() {
    var childModel =
        childModel()
            .withToolCallsTurn(Usage.of(900, 0), new ToolCall("c-lookup", "lookup", Map.of()))
            .withTextTurn("found")
            .build();
    var options =
        parent(
                callingMatching(Usage.of(100, 20)),
                child(childModel).withTool(answering("lookup", "ada")))
            .withLimits(SessionLimits.newBuilder().withMaxBudgetMicroUsd(1_000).build())
            .build();

    var result = run(options);

    var overBudget = assertInstanceOf(ResultMessage.ErrorMaxBudgetUsd.class, result);
    assertEquals(1_020, overBudget.microUsdSpent());
    assertEquals(
        new Usage(1_000, 20, 0, 0, 1_020, 100, 0),
        result.usage(),
        "the parent's own last request is its prompt");
    assertEquals("ErrorMaxBudgetUsd", childSpan("helios.terminal"));
  }

  @Test
  void aChildsOwnLowerBudgetBoundsItUnderAParentBudget() {
    var childModel =
        childModel()
            .withToolCallsTurn(Usage.of(500, 0), new ToolCall("c-lookup", "lookup", Map.of()))
            .withTextTurn("found")
            .build();
    var parentModel = callingMatching(Usage.of(100, 20));
    var options =
        parent(
                parentModel,
                child(childModel)
                    .withTool(answering("lookup", "ada"))
                    .withLimits(SessionLimits.newBuilder().withMaxBudgetMicroUsd(300).build()))
            .withLimits(SessionLimits.newBuilder().withMaxBudgetMicroUsd(1_000).build())
            .build();

    var result = run(options);

    assertInstanceOf(ResultMessage.Success.class, result);
    assertEquals(
        "Agent: subagent \"matching\" ended ErrorMaxBudgetUsd: it spent 500 micro-USD, past its"
            + " budget",
        agentResult(parentModel));
    assertEquals(CostEstimate.ofMicroUsd(620), result.cost());
  }

  @Test
  void aCallIsBoundedByItsSubagentsWallClockNotByTheParentsToolTimeout() {
    var parentModel = callingMatching(Usage.of(0, 0));
    var options =
        parent(parentModel, child(childModel().withTextTurn("found").build()))
            .withLimits(
                SessionLimits.newBuilder().withToolTimeoutDefault(Duration.ofNanos(1)).build())
            .build();

    run(options);

    assertEquals("found", agentResult(parentModel));
  }

  /** A child whose model call blocks until cancelled, counting down {@code started} first. */
  private SessionOptions.Builder stalledChild(CountDownLatch started, RecordingProvider provider) {
    return child(childModel().withStreamTurn(ModelStreams.stalled(started::countDown)).build())
        .withExecutionProvider(provider)
        .withLimits(
            SessionLimits.newBuilder()
                .withMaxWallClock(Duration.ofMinutes(30))
                .withStopGrace(NOT_REACHED)
                .build());
  }

  static Stream<Arguments> waysTheParentStopsItsCall() {
    return Stream.of(
        Arguments.of("an interrupt", ResultMessage.Cancelled.class),
        Arguments.of("a close", ResultMessage.Cancelled.class),
        Arguments.of("its wall clock", ResultMessage.ErrorMaxWallClock.class));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("waysTheParentStopsItsCall")
  void whatStopsTheParentsRunCancelsTheChildAndClosesIt(
      String how, Class<? extends ResultMessage> parentTerminal) {
    var started = new CountDownLatch(1);
    var closed = new CountDownLatch(1);
    var provider = new RecordingProvider(new ArrayList<>());
    provider.end = closed::countDown;
    var options = parent(callingMatching(Usage.of(0, 0)), stalledChild(started, provider)).build();

    try (var session = AgentSession.open(options)) {
      var result = session.send("find a match");
      Await.latch("the child's model call", started);
      switch (how) {
        case "an interrupt" -> session.interrupt();
        case "a close" -> session.close();
        default -> ((AgentSessionImpl) session).lifecycle.expireWallClock();
      }
      assertInstanceOf(parentTerminal, Await.value("the parent's result", result));
      Await.latch("the child's close", closed);
    }

    assertEquals("Cancelled", childSpan("helios.terminal"));
    assertEquals(
        "Threw",
        capture.only("execute_tool Agent").getAttributes().get(stringKey("helios.tool.outcome")));
  }

  /** A parent run whose state its {@code PreToolUseHook} records in {@code parentRun}. */
  private SessionOptions.Builder recordingParent(
      AtomicReference<SessionState> parentRun, SessionOptions.Builder child, Duration grace) {
    return parent(callingMatching(Usage.of(100, 10)), child)
        .withLimits(SessionLimits.newBuilder().withStopGrace(grace).build())
        .withHook(
            (PreToolUseHook)
                (call, ctx) -> {
                  parentRun.set(Run.stateOfThisThread());
                  return HookOutcome.cont();
                });
  }

  /** Interrupt the parent once {@code started} opens, and return its result. */
  private static ResultMessage interrupted(SessionOptions options, CountDownLatch started) {
    try (var session = AgentSession.open(options)) {
      var running = session.send("find a match");
      Await.latch("the child's model call", started);
      session.interrupt();
      return Await.value("the parent's result", running);
    }
  }

  @Test
  void anInterruptedParentsResultCountsTheSpendItsChildAddsWhileStopping() {
    var parentRun = new AtomicReference<SessionState>();
    var started = new CountDownLatch(1);
    var stream =
        EndingSessions.lingering(
            Usage.of(50, 5),
            started,
            () -> Await.until("the parent's end decided", () -> parentRun.get().isTerminal()));
    var child =
        stalledChild(started, new RecordingProvider(new ArrayList<>()))
            .withModel(childModel().withStreamTurn(stream).build());

    var result = interrupted(recordingParent(parentRun, child, NOT_REACHED).build(), started);

    assertInstanceOf(ResultMessage.Cancelled.class, result);
    assertEquals(CostEstimate.ofMicroUsd(110 + 55), result.cost());
    assertEquals(110 + 55, result.usage().totalTokens());
  }

  @Test
  void aChildsSpendCountsInTheParentsResultThoughItsTeardownOutlastsTheParentsGrace() {
    var parentRun = new AtomicReference<SessionState>();
    var started = new CountDownLatch(1);
    var provider = new RecordingProvider(new ArrayList<>());
    provider.end = () -> Await.until("the parent's run ended", () -> parentRun.get().isTerminal());
    var child =
        stalledChild(started, provider)
            .withModel(
                childModel()
                    .withToolCallsTurn(
                        Usage.of(50, 5), new ToolCall("c-lookup", "lookup", Map.of()))
                    .withStreamTurn(ModelStreams.stalled(started::countDown))
                    .build())
            .withTool(answering("lookup", "ada"));

    var result =
        interrupted(recordingParent(parentRun, child, Duration.ofNanos(1)).build(), started);

    assertInstanceOf(ResultMessage.Cancelled.class, result);
    assertEquals(CostEstimate.ofMicroUsd(110 + 55), result.cost());
    assertEquals(110 + 55, result.usage().totalTokens());
  }

  @Test
  void aCancelledCallClosesAChildHeldInAHookWithoutWaitingForItsRun() {
    var held = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var tornDown = new CountDownLatch(1);
    var provider = new RecordingProvider(new ArrayList<>());
    provider.end = tornDown::countDown;
    var holding =
        child(childModel().withTextTurn("found").build())
            .withExecutionProvider(provider)
            .withHook(
                (OnStreamEventHook)
                    (event, ctx) -> {
                      if (event instanceof QueryEvent.RunStarted) {
                        held.countDown();
                        Uninterruptible.await(release);
                      }
                    })
            .withLimits(SessionLimits.newBuilder().withStopGrace(Duration.ofMillis(1)).build());
    var options =
        parent(callingMatching(Usage.of(0, 0)), holding)
            .withLimits(SessionLimits.newBuilder().withStopGrace(Duration.ofMillis(1)).build())
            .build();

    try (var session = AgentSession.open(options)) {
      var result = session.send("find a match");
      Await.latch("the child's hook", held);
      session.interrupt();
      Await.latch("the child's teardown while its run is held", tornDown);
      assertInstanceOf(ResultMessage.Cancelled.class, Await.value("the parent's result", result));
    } finally {
      release.countDown();
    }
  }

  @Test
  void theCallsDeadlineInterruptsTheChildAndWaitsItsGraceForItsCloseNotTheParents() {
    var started = new CountDownLatch(1);
    var tearingDown = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var provider = new RecordingProvider(new ArrayList<>());
    provider.end =
        () -> {
          tearingDown.countDown();
          Uninterruptible.await(release);
        };
    var parentModel = callingMatching(Usage.of(0, 0));
    var options =
        parent(parentModel, stalledChild(started, provider))
            .withLimits(SessionLimits.newBuilder().withStopGrace(Duration.ofNanos(1)).build())
            .build();

    try (var session = AgentSession.open(options)) {
      var result = session.send("find a match");
      Await.latch("the child's model call", started);
      ((AgentSessionImpl) session).lifecycle.expireToolCalls();
      Await.latch("the child's teardown", tearingDown);
      release.countDown();
      assertInstanceOf(ResultMessage.Success.class, Await.value("the parent's result", result));
    }

    assertEquals(
        "Agent: subagent \"matching\" ended Cancelled: interrupted", agentResult(parentModel));
    assertEquals("Cancelled", childSpan("helios.terminal"));
    assertEquals(1, provider.ends.get());
  }

  @Test
  void aPreToolUseHookThatBlocksTheCallStartsNoChildAndTheModelReadsWhy() {
    var parentModel = callingMatching(Usage.of(0, 0));
    var options =
        parent(parentModel, child(childModel().withTextTurn("found").build()))
            .withHook(
                new PreToolUseHook() {
                  @Override
                  public String name() {
                    return "no-spawn";
                  }

                  @Override
                  public HookOutcome.PreToolUse beforeTool(ToolCall call, HookContext ctx) {
                    return HookOutcome.block("no subagents today");
                  }
                })
            .build();

    run(options);

    assertEquals("blocked by no-spawn: no subagents today", agentResult(parentModel));
    assertEquals(List.of(), children.getFirst().calls());
  }

  @Test
  void aDenyRuleOnOneSubagentStartsNoChildAndTheModelReadsWhy() {
    var parentModel = callingMatching(Usage.of(0, 0));
    var options =
        parent(parentModel, child(childModel().withTextTurn("found").build()))
            .withPermission(
                new Permission(
                    PermissionMode.DEFAULT,
                    List.of(PermissionRule.any(AgentTool.NAME)),
                    List.of(),
                    List.of(PermissionRule.of(AgentTool.NAME, "matching"))))
            .build();

    run(options);

    assertTrue(
        agentResult(parentModel).startsWith("blocked by Permission: ")
            && agentResult(parentModel).contains("Agent(matching)"),
        agentResult(parentModel));
    assertEquals(List.of(), children.getFirst().calls());
  }

  @Test
  void aChildCallItsPolicyAsksAboutIsDeniedWithTheReasonItsModelReads() {
    var write =
        ToolBinding.newBuilder(answering("write", "written").tool())
            .withCategory(ToolCategory.WRITE)
            .build();
    var childModel =
        childModel()
            .withToolCallsTurn(new ToolCall("c-write", "write", Map.of()))
            .withTextTurn("could not write")
            .build();
    var parentModel = callingMatching(Usage.of(0, 0));

    run(parent(parentModel, child(childModel).withTool(write)).build());

    assertEquals("could not write", agentResult(parentModel));
    assertEquals(
        "blocked by Permission: " + AgentTool.NO_ONE_TO_ASK,
        childModel.calls().get(1).getLast().content());
  }

  @Test
  void aCallNamingNoSubagentFailsListingTheNamesAndStartsNoChild() {
    var parentModel =
        parentModel()
            .withToolCallsTurn(agent("nobody", "find a match"))
            .withTextTurn("answered")
            .build();
    var options =
        parent(parentModel, child(childModel().withTextTurn("found").build()))
            .withSubagent(new Subagent("scoring", "scores", child(childModel().build()).build()))
            .build();

    run(options);

    assertEquals(
        "Agent: no subagent is named \"nobody\"; the subagents are [matching, scoring]",
        agentResult(parentModel));
    assertTrue(children.stream().allMatch(model -> model.calls().isEmpty()));
  }

  @Test
  void aCallWithABlankPromptFails() {
    var parentModel =
        parentModel().withToolCallsTurn(agent("matching", " ")).withTextTurn("answered").build();

    run(parent(parentModel, child(childModel().build())).build());

    assertEquals("Agent: missing required 'prompt'", agentResult(parentModel));
  }

  @Test
  void aChildThatCannotOpenIsAFailureTheModelReads() {
    var provider = new RecordingProvider(new ArrayList<>());
    provider.start = () -> SessionStartOutcome.refuse("no sandbox free");
    var parentModel = callingMatching(Usage.of(0, 0));

    var result =
        run(
            parent(parentModel, child(childModel().build()).withExecutionProvider(provider))
                .build());

    assertInstanceOf(ResultMessage.Success.class, result);
    assertTrue(
        agentResult(parentModel).startsWith("Agent: subagent \"matching\" could not start: ")
            && agentResult(parentModel).contains("no sandbox free"),
        agentResult(parentModel));
  }

  @Test
  void aChildThatCannotStartIsAFailureRedactedByTheChildsOwnSecrets() {
    var secrets = new SecretRegistry();
    secrets.register("CHILD_KEY", "child-secret-0123456789");
    var parentModel = callingMatching(Usage.of(0, 0));
    var failing =
        RecordingStore.loadThrowing(
            new CompletionException(
                new SessionStoreException("store refused child-secret-0123456789", null)));

    run(
        parent(
                parentModel,
                child(childModel().build()).withSecretRegistry(secrets).withStore(failing))
            .build());

    assertEquals(
        "Agent: subagent \"matching\" could not start: "
            + SessionStoreException.class.getName()
            + ": store refused <redacted:CHILD_KEY>",
        agentResult(parentModel));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("childAnswersNamingASecret")
  void aChildsAnswerIsRedactedByTheChildsOwnSecrets(String expected, ChildScript script) {
    var secrets = new SecretRegistry();
    secrets.register("CHILD_KEY", "child-secret-0123456789");
    var parentModel = callingMatching(Usage.of(0, 0));

    run(
        parent(parentModel, child(script.script(childModel()).build()).withSecretRegistry(secrets))
            .build());

    assertEquals(expected, agentResult(parentModel));
  }

  @Test
  void aSecretTheChildRegistersWhileItRunsIsRedactedFromItsAnswer() {
    var secrets = new SecretRegistry();
    var provider = new RecordingProvider(new ArrayList<>());
    provider.start =
        () -> {
          secrets.register("CHILD_KEY", "child-secret-0123456789");
          return SessionStartOutcome.accept();
        };
    var parentModel = callingMatching(Usage.of(0, 0));
    var child =
        child(childModel().withTextTurn("answer child-secret-0123456789").build())
            .withSecretRegistry(secrets)
            .withExecutionProvider(provider);

    run(parent(parentModel, child).build());

    assertEquals("answer <redacted:CHILD_KEY>", agentResult(parentModel));
  }

  static Stream<Arguments> childAnswersNamingASecret() {
    return Stream.of(
        Arguments.of(
            "answer <redacted:CHILD_KEY>",
            (ChildScript) model -> model.withTextTurn("answer child-secret-0123456789")),
        Arguments.of(
            "Agent: subagent \"matching\" ended Refusal: not <redacted:CHILD_KEY>",
            (ChildScript) model -> model.withRefusalTurn("not child-secret-0123456789")));
  }

  @Test
  void aChildsHostErrorFailsTheParentAsItself() {
    var error = new AssertionError("child host error");
    var childModel =
        childModel().withToolCallsTurn(new ToolCall("c-lookup", "lookup", Map.of())).build();
    var options =
        parent(
                callingMatching(Usage.of(0, 0)),
                child(childModel)
                    .withTool(
                        EndingSessions.binding(
                            "lookup",
                            (arguments, context) -> {
                              throw error;
                            })))
            .build();

    try (var session = AgentSession.open(options)) {
      assertSame(error, Await.failure("the parent's result", session.send("find a match")));
    }
  }

  @Test
  void aHostErrorOpeningTheChildFailsTheParentAsItself() {
    var error = new AssertionError("store host error");
    var options =
        parent(
                callingMatching(Usage.of(0, 0)),
                child(childModel().build())
                    .withStore(RecordingStore.loadThrowing(new CompletionException(error))))
            .build();

    try (var session = AgentSession.open(options)) {
      assertSame(error, Await.failure("the parent's result", session.send("find a match")));
    }
  }

  @Test
  void aChildRunThatCrashesIsAFailureRedactedByTheChildsOwnSecrets() {
    var secrets = new SecretRegistry();
    secrets.register("CHILD_KEY", "child-secret-0123456789");
    var parentModel = callingMatching(Usage.of(0, 0));
    var child =
        child(childModel().withTextTurn("never").build())
            .withSecretRegistry(secrets)
            .withClock(EndingSessions.crashingRuns("clock failure child-secret-0123456789"));

    var result = run(parent(parentModel, child).build());

    assertInstanceOf(ResultMessage.Success.class, result);
    assertEquals(
        "Agent: subagent \"matching\" crashed: java.lang.IllegalStateException: clock failure"
            + " <redacted:CHILD_KEY>",
        agentResult(parentModel));
  }

  @Test
  void anErrorOfTheChildsCloseFailsTheParentAsItself() {
    var error = new AssertionError("child close error");
    var provider = new RecordingProvider(new ArrayList<>());
    provider.end =
        () -> {
          throw error;
        };
    var options =
        parent(
                callingMatching(Usage.of(0, 0)),
                child(childModel().withTextTurn("found").build()).withExecutionProvider(provider))
            .build();

    try (var session = AgentSession.open(options)) {
      assertSame(error, Await.failure("the parent's result", session.send("find a match")));
    }
  }

  static Stream<Arguments> unsuccessfulChildren() {
    Consumer<SessionOptions.Builder> none = builder -> {};
    return Stream.of(
        Arguments.of(
            "ErrorMaxTurns: it used its 1 turns",
            (ChildScript)
                model -> model.withToolCallsTurn(new ToolCall("c-lookup", "lookup", Map.of())),
            (Consumer<SessionOptions.Builder>)
                builder ->
                    builder
                        .withTool(answering("lookup", "ada"))
                        .withLimits(SessionLimits.newBuilder().withMaxTurns(1).build())),
        Arguments.of(
            "ErrorDuringExecution: Scripted turns exhausted: 0 turn(s) scripted, call 1 requested",
            (ChildScript) model -> model,
            none),
        Arguments.of(
            "ErrorTransientStream: overloaded",
            (ChildScript)
                model ->
                    model.withStreamTurn(
                        ModelStreams.failing(
                            new TransientStreamException("overloaded", null, "scripted"))),
            (Consumer<SessionOptions.Builder>)
                builder ->
                    builder.withLimits(
                        SessionLimits.newBuilder()
                            .withStreamRetryPolicy(StreamRetryPolicy.disabled())
                            .build())),
        Arguments.of(
            "Refusal: I cannot help with that",
            (ChildScript) model -> model.withRefusalTurn("I cannot help with that"),
            none),
        Arguments.of(
            "ErrorMaxWallClock: it ran PT",
            (ChildScript) model -> model.withStreamTurn(ModelStreams.stalled(() -> {})),
            (Consumer<SessionOptions.Builder>)
                builder ->
                    builder.withLimits(
                        SessionLimits.newBuilder()
                            .withMaxWallClock(Duration.ofMillis(1))
                            .withStopGrace(NOT_REACHED)
                            .build())));
  }

  /** How a test scripts a child's model. */
  @FunctionalInterface
  interface ChildScript {
    ScriptedModel.Builder script(ScriptedModel.Builder model);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("unsuccessfulChildren")
  void aChildThatDoesNotSucceedIsAFailureNamingTheSubagentItsTerminalAndWhy(
      String ended, ChildScript script, Consumer<SessionOptions.Builder> limits) {
    var parentModel = callingMatching(Usage.of(0, 0));
    var child = child(script.script(childModel()).build());
    limits.accept(child);

    run(parent(parentModel, child).build());

    assertTrue(
        agentResult(parentModel).startsWith("Agent: subagent \"matching\" ended " + ended),
        agentResult(parentModel));
  }

  @Test
  void theToolDescribesEachSubagentAndKeysAPermissionByTheNamedOne() {
    var options = child(childModel().build()).build();
    var binding =
        new AgentTool(
                List.of(
                    new Subagent("matching", "finds a match", options),
                    new Subagent("scoring", "scores a match", options)),
                SessionLimits.defaults())
            .binding();

    assertEquals(ToolCategory.EXECUTION, binding.category());
    assertTrue(
        binding
            .tool()
            .description()
            .endsWith("The subagents:\nmatching: finds a match\nscoring: scores a match"),
        binding.tool().description());
    assertEquals(
        Map.of(
            "type",
            "object",
            "properties",
            Map.of(
                "agent",
                Map.of(
                    "type",
                    "string",
                    "description",
                    "The subagent's name, one of [matching, scoring]"),
                "prompt",
                Map.of(
                    "type",
                    "string",
                    "description",
                    "The task, with everything the subagent needs to know.")),
            "required",
            List.of("agent", "prompt")),
        binding.tool().parametersAsJsonSchema());
    assertEquals(
        new ToolPermissionKey(AgentTool.NAME, "scoring"),
        binding.canonicalize(agent("scoring", "p")).key());
  }
}
