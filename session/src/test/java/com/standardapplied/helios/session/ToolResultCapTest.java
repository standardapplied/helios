/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.model.CancellationToken;
import com.standardapplied.helios.core.model.InlineFile;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Role;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.tool.SessionContext;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolExecutor;
import com.standardapplied.helios.core.tool.ToolOutcome;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.execution.ExecutionProvider;
import com.standardapplied.helios.session.execution.ExecutionRequest;
import com.standardapplied.helios.session.execution.ExecutionResult;
import com.standardapplied.helios.session.execution.Runtime;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.PostToolUseHook;
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.permissions.PermissionMode;
import com.standardapplied.helios.session.store.InMemorySessionStore;
import com.standardapplied.helios.session.test.EventCollector;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * The session bounds each tool result it puts in history: its start, a marker and its end enter
 * history, while the {@link QueryEvent.ToolResult} event and {@code PostToolUse} hooks keep the
 * whole redacted output.
 */
final class ToolResultCapTest {

  private static final int LIMIT = 1_024;
  private static final ToolCall CALL = new ToolCall("c1", "big", Map.of());

  private record Run(EventCollector events, ScriptedModel model) {

    List<QueryEvent.ToolResult> toolResults() {
      return events.eventsOf(QueryEvent.ToolResult.class);
    }

    /** The tool messages of the model's {@code call}-th request, in order. */
    List<String> toolMessages(int call) {
      return toolTexts(model.calls().get(call));
    }
  }

  private static List<String> toolTexts(List<Message> messages) {
    return messages.stream().filter(m -> m.role() == Role.TOOL).map(Message::content).toList();
  }

  /** {@code length} chars whose first and second halves differ, so a cut shows what it kept. */
  private static String output(int length) {
    return "a".repeat(length / 2) + "z".repeat(length - length / 2);
  }

  /** {@code text} as history holds it under {@code limit}, with an even count of chars kept. */
  private static String cut(String text, int limit) {
    return text.substring(0, limit / 2)
        + "\n[output truncated: "
        + (text.length() - limit)
        + " chars cut; the tool result limit is "
        + limit
        + " chars]\n"
        + text.substring(text.length() - limit / 2);
  }

  private static ToolBinding tool(ToolExecutor executor) {
    return ToolBinding.newBuilder(
            Tool.newBuilder().withName("big").withDescription("big").withExecutor(executor).build())
        .withCategory(ToolCategory.READ)
        .build();
  }

  private static ToolBinding returning(String output) {
    return tool((args, ctx) -> ToolResult.success(output));
  }

  private static SessionOptions.Builder options(ToolBinding binding) {
    return SessionOptions.newBuilder()
        .withTools(List.of(binding))
        .withLimits(SessionLimits.newBuilder().withMaxToolResultChars(LIMIT).build());
  }

  private static ScriptedModel callingThenDone(ToolCall call) {
    return ScriptedModel.newBuilder().withToolCallsTurn(call).withTextTurn("done").build();
  }

  private static Run run(SessionOptions.Builder options, ScriptedModel model) {
    var events = new EventCollector();
    try (var session = AgentSession.open(options.withModel(model).build())) {
      events.read(session.events());
      session.runBlocking(UserMessage.text("go"));
      events.awaitRunEnded();
      return new Run(events, model);
    }
  }

  @Test
  void aResultOverTheCapIsCutForTheModelAndWholeForEventsAndHooks() {
    var whole = output(5_000);
    var hookSaw = new CopyOnWriteArrayList<String>();
    var options =
        options(returning(whole))
            .withHook(
                (PostToolUseHook)
                    (call, result, ctx) -> {
                      hookSaw.add(result.output());
                      return new HookOutcome.Continue();
                    });

    var run = run(options, callingThenDone(CALL));

    var event = run.toolResults().getFirst();
    assertEquals(whole, event.result().output());
    assertInstanceOf(ToolOutcome.Succeeded.class, event.result().outcome());
    assertEquals(List.of(whole), hookSaw);
    assertEquals(
        List.of(
            "a".repeat(512)
                + "\n[output truncated: 3976 chars cut; the tool result limit is 1024 chars]\n"
                + "z".repeat(512)),
        run.toolMessages(1));
  }

  @Test
  void aResultAtTheCapEntersHistoryUnchanged() {
    var whole = output(LIMIT);

    var run = run(options(returning(whole)), callingThenDone(CALL));

    assertEquals(List.of(whole), run.toolMessages(1));
  }

  @Test
  void aResultOneCharOverTheCapIsCut() {
    var whole = output(LIMIT + 1);

    var run = run(options(returning(whole)), callingThenDone(CALL));

    assertEquals(whole, run.toolResults().getFirst().result().output());
    assertEquals(List.of(cut(whole, LIMIT)), run.toolMessages(1));
  }

  @Test
  void aSecretTheCutFallsAcrossIsRedactedFirst() {
    var secrets = new SecretRegistry();
    secrets.register("API_KEY", "sk-test-0123456789abcdef");
    var straddling = "a".repeat(500) + "sk-test-0123456789abcdef" + "z".repeat(4_476);
    var options = options(returning(straddling)).withSecretRegistry(secrets);

    var run = run(options, callingThenDone(CALL));

    var redacted = "a".repeat(500) + "<redacted:API_KEY>" + "z".repeat(4_476);
    var event = run.toolResults().getFirst();
    assertEquals(Map.of("API_KEY", 1), event.redactions());
    assertEquals(redacted, event.result().output());
    assertEquals(List.of(cut(redacted, LIMIT)), run.toolMessages(1));
  }

  @Test
  void aMutatedResultIsCutForTheModelAndWholeForItsEvent() {
    var mutated = output(5_000);
    var options =
        options(returning("small"))
            .withHook(
                (PostToolUseHook) (call, result, ctx) -> new HookOutcome.MutateResult(mutated));

    var run = run(options, callingThenDone(CALL));

    var results = run.toolResults();
    assertEquals(2, results.size());
    assertEquals(mutated, results.get(1).result().output());
    assertEquals(List.of(cut(mutated, LIMIT)), run.toolMessages(1));
  }

  @Test
  void aResultAHookStoppedOnIsCutInHistoryAndInTheStore() {
    var whole = output(5_000);
    var store = new InMemorySessionStore();
    var options =
        options(returning(whole))
            .withStore(store)
            .withHook((PostToolUseHook) (call, result, ctx) -> new HookOutcome.Stop("stopped"));
    var model = ScriptedModel.newBuilder().withToolCallsTurn(CALL).withTextTurn("next").build();
    var built = options.withModel(model).build();

    try (var session = AgentSession.open(built)) {
      session.runBlocking(UserMessage.text("go"));
      session.runBlocking(UserMessage.text("again"));
    }

    var cut = cut(whole, LIMIT);
    assertEquals(List.of(cut), toolTexts(model.calls().get(1)));
    assertEquals(List.of(cut), toolTexts(store.load(built.sessionId()).history()));
  }

  @Test
  void theTextOfAToolThatThrewIsCut() {
    var message = output(5_000);
    var binding =
        tool(
            (args, ctx) -> {
              throw new IllegalStateException(message);
            });

    var run = run(options(binding), callingThenDone(CALL));

    var whole = "tool 'big' threw: " + message;
    assertEquals(whole, run.toolResults().getFirst().result().output());
    assertEquals(List.of(cut(whole, LIMIT)), run.toolMessages(1));
  }

  @Test
  void attachmentsAreNotCut() {
    var whole = output(5_000);
    var png = InlineFile.of(new byte[] {(byte) 0x89, 'P', 'N', 'G'}, "image/png");
    var binding = tool((args, ctx) -> ToolResult.successWithAttachments(whole, List.of(png)));

    var run = run(options(binding), callingThenDone(CALL));

    assertEquals(List.of(cut(whole, LIMIT)), run.toolMessages(1));
    var followUp = run.model().calls().get(1).getLast();
    assertEquals(List.of(png), followUp.inlineFiles());
  }

  @Test
  void anExecuteResultKeepsTheEndOfItsStderrUnderTheDefaultLimit() {
    var provider =
        new FixedOutputProvider(
            new ExecutionResult(
                1, "o".repeat(50_000), "e".repeat(49_996) + "boom", Duration.ZERO, false));
    var options =
        SessionOptions.newBuilder()
            .withExecutionProvider(provider)
            .withPermission(Permission.empty(PermissionMode.BYPASS_PERMISSIONS));
    var execute = new ToolCall("c1", "Execute", Map.of("runtime", "BASH", "script", "run"));

    var run = run(options, callingThenDone(execute));

    var message = run.toolMessages(1).getFirst();
    assertTrue(message.contains("[output truncated: "), message);
    assertTrue(message.endsWith("boom"));
  }

  @Test
  void theDefaultLimitKeepsTheFirstAndLast32768Chars() {
    var whole = output(70_000);
    var options = SessionOptions.newBuilder().withTools(List.of(returning(whole)));

    var run = run(options, callingThenDone(CALL));

    assertEquals(List.of(cut(whole, 65_536)), run.toolMessages(1));
  }

  /** Answers every script with one result. */
  private record FixedOutputProvider(ExecutionResult result) implements ExecutionProvider {

    @Override
    public Set<Runtime> supportedRuntimes() {
      return Set.of(Runtime.BASH);
    }

    @Override
    public ExecutionResult execute(
        SessionContext session, ExecutionRequest request, CancellationToken cancellation) {
      return result;
    }
  }
}
