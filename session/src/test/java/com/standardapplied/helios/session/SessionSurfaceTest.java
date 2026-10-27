/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.BlockingModel;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolOutcome;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.ask.AskUserQuestionTool;
import com.standardapplied.helios.session.execution.ExecuteTool;
import com.standardapplied.helios.session.memory.MemoryWriteTool;
import com.standardapplied.helios.session.test.EventCollector;
import com.standardapplied.helios.session.test.QuestionAnswers;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.testing.ScriptedModel;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a user configures and what the session wires from it: tools append, an execution provider
 * registers {@code Execute}, {@code AskUserQuestion} is opt-in, two tools never share a name, and
 * the loop machinery is not part of the module's API.
 */
final class SessionSurfaceTest {

  private static final ToolCall CALL_MINE = new ToolCall("c1", "Mine", Map.of());
  private static final ToolCall EXECUTE =
      new ToolCall("c1", ExecuteTool.NAME, Map.of("runtime", "bash", "script", "echo hi"));
  private static final ToolCall ASK =
      new ToolCall(
          "c1",
          AskUserQuestionTool.NAME,
          Map.of(
              "question",
              "Which colour?",
              "options",
              List.of(Map.of("label", "Red"), Map.of("label", "Blue"))));

  @TempDir Path root;

  private final List<String> ran = new CopyOnWriteArrayList<>();
  private final RecordingProvider provider = new RecordingProvider(new ArrayList<>());

  /** A {@code READ} tool named {@code name} that records each run. */
  private ToolBinding tool(String name) {
    return ToolBinding.newBuilder(
            Tool.newBuilder()
                .withName(name)
                .withDescription("records its run")
                .withExecutor(
                    (args, ctx) -> {
                      ran.add(name);
                      return ToolResult.success("ran " + name);
                    })
                .build())
        .withCategory(ToolCategory.READ)
        .build();
  }

  // ── tools append ─────────────────────────────────────────────────────────

  @Test
  void aToolAddedToAPresetIsOfferedWithThePresetsAndBuiltInToolsAndRuns() {
    var model = new OfferingModel(answering(CALL_MINE));

    var run = run(model, SessionPresets.workspace(model, root).withTool(tool("Mine")));

    assertInstanceOf(ResultMessage.Success.class, run.terminal());
    assertEquals(
        List.of("Read", "Glob", "Grep", "LS", "Mine", "MemoryRead", "MemoryWrite"),
        model.offered.getFirst());
    assertEquals(List.of("Mine"), ran);
  }

  @Test
  void toolsSetAfterAnAddedToolReplaceIt() {
    var model = new OfferingModel(answering());

    run(model, minimal(model).withTool(tool("B")).withTools(List.of(tool("A"))));

    assertEquals(List.of("A"), model.offered.getFirst());
  }

  @Test
  void twoToolsWithOneNameFailTheSessionsCreation() {
    var options = minimal(answering()).withTool(tool("X")).withTool(tool("X")).build();

    var thrown = assertThrows(IllegalArgumentException.class, () -> AgentSession.create(options));

    assertEquals("duplicate tool name in registry: X", thrown.getMessage());
  }

  @Test
  void aUserToolNamedLikeAMemoryToolFailsASessionWithAMemoryBackend() {
    var options =
        SessionPresets.workspace(answering(), root).withTool(tool(MemoryWriteTool.NAME)).build();

    var thrown = assertThrows(IllegalArgumentException.class, () -> AgentSession.create(options));

    assertEquals("duplicate tool name in registry: MemoryWrite", thrown.getMessage());
  }

  // ── visibility decides what a turn advertises ────────────────────────────

  @Test
  void aToolVisibleFromTheSecondTurnIsOfferedOnlyThen() {
    var model = new OfferingModel(answering(CALL_MINE));
    var later =
        ToolBinding.newBuilder(tool("Later").tool())
            .withCategory(ToolCategory.READ)
            .withVisibility(ctx -> ctx.turnIndex() > 1)
            .build();

    run(model, minimal(model).withTool(tool("Mine")).withTool(later));

    assertEquals(List.of(List.of("Mine"), List.of("Mine", "Later")), model.offered);
  }

  // ── an execution provider registers Execute ──────────────────────────────

  @Test
  void anExecutionProviderIsOfferedAsExecuteStartedEndedAndCalledOnceApproved() {
    var model = new OfferingModel(answering(EXECUTE));

    var run =
        run(
            model,
            minimal(model).withExecutionProvider(provider),
            session -> QuestionAnswers.selecting(session, "Allow"));

    assertInstanceOf(ResultMessage.Success.class, run.terminal());
    assertEquals(List.of(ExecuteTool.NAME), model.offered.getFirst());
    assertEquals(1, run.events().eventsOf(QueryEvent.QuestionAsked.class).size());
    assertEquals(List.of("echo hi"), provider.executed.stream().map(r -> r.script()).toList());
    assertEquals(1, provider.starts.get());
    assertEquals(1, provider.ends.get());
  }

  @Test
  void withoutAProviderNoRequestOffersExecuteAndACallToItIsNotFound() {
    var model = new OfferingModel(answering(EXECUTE));

    var run = run(model, minimal(model));

    assertInstanceOf(ResultMessage.Success.class, run.terminal());
    assertEquals(List.of(List.of(), List.of()), model.offered);
    assertEquals(new ToolOutcome.NotFound(), onlyResult(run).outcome());
  }

  @Test
  void anExecuteBindingNextToItsProviderFailsTheSessionsCreationBeforeItStarts() {
    var options =
        minimal(answering())
            .withExecutionProvider(provider)
            .withTool(ExecuteTool.binding(provider))
            .build();

    var thrown = assertThrows(IllegalArgumentException.class, () -> AgentSession.create(options));

    assertEquals("duplicate tool name in registry: Execute", thrown.getMessage());
    assertEquals(0, provider.starts.get());
  }

  // ── AskUserQuestion is opt-in ────────────────────────────────────────────

  @Test
  void withoutTheOptInNoRequestOffersAskUserQuestionAndACallToItIsNotFound() {
    var model = new OfferingModel(answering(ASK));

    var run = run(model, minimal(model));

    assertEquals(List.of(List.of(), List.of()), model.offered);
    assertEquals(new ToolOutcome.NotFound(), onlyResult(run).outcome());
    assertEquals(List.of(), run.events().eventsOf(QueryEvent.QuestionAsked.class));
  }

  @Test
  void withTheOptInAskUserQuestionIsOfferedAndReturnsTheAnswer() {
    var model = new OfferingModel(answering(ASK));

    var run =
        run(
            model,
            minimal(model).withAskUserQuestion(),
            session -> QuestionAnswers.selecting(session, "Blue"));

    assertEquals(List.of(AskUserQuestionTool.NAME), model.offered.getFirst());
    assertEquals(1, run.events().eventsOf(QueryEvent.QuestionAsked.class).size());
    var result = onlyResult(run);
    assertTrue(result.succeeded());
    assertTrue(result.output().contains("Blue"), result.output());
  }

  @Test
  void aCallThePolicyAsksAboutIsApprovedWithoutTheOptIn() {
    var sessions = new WriteToolSessions();
    var model = new OfferingModel(answering(WriteToolSessions.write("notes.txt")));

    var run =
        run(
            model,
            minimal(model).withTool(sessions.writeTool()),
            session -> QuestionAnswers.selecting(session, "Allow"));

    assertFalse(model.offered.getFirst().contains(AskUserQuestionTool.NAME));
    assertEquals(1, run.events().eventsOf(QueryEvent.QuestionAsked.class).size());
    assertEquals(List.of(Map.of("path", "notes.txt")), sessions.executed);
  }

  @Test
  void aUserToolNamedAskUserQuestionFailsAnOptedInSessionsCreation() {
    var options =
        minimal(answering()).withAskUserQuestion().withTool(tool(AskUserQuestionTool.NAME)).build();

    var thrown = assertThrows(IllegalArgumentException.class, () -> AgentSession.create(options));

    assertEquals("duplicate tool name in registry: AskUserQuestion", thrown.getMessage());
  }

  // ── the loop machinery is internal ───────────────────────────────────────

  @Test
  void theLoopPackageIsNotExported() {
    assertFalse(
        AgentSession.class.getModule().isExported("com.standardapplied.helios.session.loop"));
  }

  @Test
  void noMachineryTypeIsPublicInAnExportedPackage() throws ClassNotFoundException {
    var module = AgentSession.class.getModule();
    for (var name :
        List.of(
            "loop.DefaultPermissionEvaluator",
            "loop.ToolApprover",
            "loop.ApprovalRequest",
            "loop.HookRegistry",
            "loop.HookDecision",
            "loop.PreToolUseResult",
            "loop.ToolRegistry",
            "permissions.RuleMatcher")) {
      var type = Class.forName("com.standardapplied.helios.session." + name);
      assertFalse(
          Modifier.isPublic(type.getModifiers()) && module.isExported(type.getPackageName()), name);
    }
  }

  // ── fixtures ─────────────────────────────────────────────────────────────

  /** A model that answers as {@code script} does and records the tool names of each request. */
  private static final class OfferingModel implements BlockingModel {

    private final ScriptedModel script;
    final List<List<String>> offered = new CopyOnWriteArrayList<>();

    OfferingModel(ScriptedModel script) {
      this.script = script;
    }

    @Override
    public Response<Void> chat(List<Message> messages, List<Tool> tools) {
      offered.add(tools.stream().map(Tool::name).toList());
      return script.chat(messages, tools);
    }

    @Override
    public String id() {
      return script.id();
    }

    @Override
    public String provider() {
      return script.provider();
    }
  }

  private record Run(EventCollector events, ResultMessage terminal) {}

  /** A model that makes {@code calls} in one turn, if any, then answers {@code done}. */
  private static ScriptedModel answering(ToolCall... calls) {
    return calls.length == 0
        ? ScriptedModel.newBuilder().withTextTurn("done").build()
        : WriteToolSessions.answering(calls);
  }

  private static SessionOptions.Builder minimal(Model model) {
    return SessionPresets.minimal(model);
  }

  private static ToolResult onlyResult(Run run) {
    var results = run.events().eventsOf(QueryEvent.ToolResult.class);
    assertEquals(1, results.size(), "tool results");
    return results.getFirst().result();
  }

  private static Run run(OfferingModel model, SessionOptions.Builder options) {
    return run(model, options, session -> event -> {});
  }

  private static Run run(
      OfferingModel model,
      SessionOptions.Builder options,
      Function<AgentSession, Consumer<QueryEvent>> reactions) {
    try (var session = AgentSession.create(options.withModel(model).build())) {
      var events = new EventCollector(reactions.apply(session));
      events.read(session.events());
      session.send(UserMessage.text("go"));
      var terminal = Await.value("the session to end", session.result());
      events.awaitDone();
      return new Run(events, terminal);
    }
  }
}
