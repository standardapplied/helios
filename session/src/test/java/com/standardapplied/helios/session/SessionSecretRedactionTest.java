/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.fault.Backoff;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Role;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.model.TransientStreamException;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.CommandGrant;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolExecutor;
import com.standardapplied.helios.core.tool.ToolOutcome;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.execution.ExecutionProvider;
import com.standardapplied.helios.session.execution.ExecutionRequest;
import com.standardapplied.helios.session.execution.ExecutionResult;
import com.standardapplied.helios.session.execution.Runtime;
import com.standardapplied.helios.session.execution.SessionStartOutcome;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.OnStreamEventHook;
import com.standardapplied.helios.session.hooks.PostModelTurnHook;
import com.standardapplied.helios.session.hooks.PostToolUseHook;
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.permissions.PermissionMode;
import com.standardapplied.helios.session.test.CollectingSubscriber;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolRegistry;
import com.standardapplied.helios.testing.ModelStreams;
import com.standardapplied.helios.testing.ScriptedModel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The session's one redaction pass: every tool result's output is redacted against the session's
 * secret registry before its event, a {@code PostToolUse} hook or the model sees it, and every
 * error the session reports has the messages of its chain redacted.
 */
final class SessionSecretRedactionTest {

  private static final String SECRET = "sk-test-0123456789abcdef";
  private static final String MARKER = "<redacted:API_KEY>";

  private record Run(
      ResultMessage result,
      CollectingSubscriber events,
      ScriptedModel model,
      SecretRegistry secrets) {

    List<QueryEvent.ToolResult> toolResults() {
      return events.eventsOf(QueryEvent.ToolResult.class);
    }

    /** The tool messages of the model's {@code call}-th request, in order. */
    List<String> toolMessages(int call) {
      return model.calls().get(call).stream()
          .filter(m -> m.role() == Role.TOOL)
          .map(Message::content)
          .toList();
    }
  }

  private static SecretRegistry registryWithSecret() {
    var registry = new SecretRegistry();
    registry.register("API_KEY", SECRET);
    return registry;
  }

  private static ToolBinding tool(String name, ToolExecutor executor) {
    return ToolBinding.newBuilder(
            Tool.newBuilder().withName(name).withDescription(name).withExecutor(executor).build())
        .withCategory(ToolCategory.READ)
        .build();
  }

  private static ToolCall call(String id, String name) {
    return new ToolCall(id, name, Map.of());
  }

  /** A model that makes each group of calls in a turn of its own, then answers "done". */
  private static ScriptedModel calling(List<List<ToolCall>> turns) {
    var builder = ScriptedModel.newBuilder();
    for (var turn : turns) {
      builder.withToolCallsTurn(turn.toArray(ToolCall[]::new));
    }
    return builder.withTextTurn("done").build();
  }

  private static Run run(SessionOptions.Builder options, ScriptedModel model) {
    var built = options.withModel(model).build();
    var events = new CollectingSubscriber();
    try (var session = AgentSession.create(built)) {
      session.events().subscribe(events);
      var result = session.runBlocking(UserMessage.text("go"));
      events.awaitDone();
      return new Run(result, events, model, built.secretRegistry());
    }
  }

  private static SessionOptions.Builder withTools(SecretRegistry secrets, ToolBinding... tools) {
    return SessionOptions.newBuilder()
        .withSecretRegistry(secrets)
        .withTools(new ToolRegistry(List.of(tools)));
  }

  @Test
  void aCustomToolsOutputIsRedactedForEveryObserver() {
    var postToolUse = new CopyOnWriteArrayList<String>();
    var streamed = new CopyOnWriteArrayList<QueryEvent.ToolResult>();
    var options =
        withTools(
                registryWithSecret(),
                tool("leak", (args, ctx) -> ToolResult.success("token=" + SECRET)))
            .withHook(
                (PostToolUseHook)
                    (toolCall, result, ctx) -> {
                      postToolUse.add(result.output());
                      return new HookOutcome.Continue();
                    })
            .withHook(
                (OnStreamEventHook)
                    (event, ctx) -> {
                      if (event instanceof QueryEvent.ToolResult result) {
                        streamed.add(result);
                      }
                    });

    var run = run(options, calling(List.of(List.of(call("c1", "leak")))));

    var expected = "token=" + MARKER;
    var event = run.toolResults().getFirst();
    assertEquals(expected, event.result().output());
    assertEquals(Map.of("API_KEY", 1), event.redactions());
    assertEquals(List.of(expected), postToolUse);
    assertEquals(expected, streamed.getFirst().result().output());
    assertEquals(List.of(expected), run.toolMessages(1));
  }

  @Test
  void redactionsCountEachSecretInTheOrderTheOutputNamesThem() {
    var secrets = registryWithSecret();
    secrets.register("DB_PASSWORD", "hunter2-hunter2");
    var options =
        withTools(
            secrets,
            tool(
                "leak",
                (args, ctx) ->
                    ToolResult.success("hunter2-hunter2 " + SECRET + " hunter2-hunter2")));

    var run = run(options, calling(List.of(List.of(call("c1", "leak")))));

    var event = run.toolResults().getFirst();
    assertEquals(
        "<redacted:DB_PASSWORD> " + MARKER + " <redacted:DB_PASSWORD>", event.result().output());
    assertEquals(
        List.of(Map.entry("DB_PASSWORD", 2), Map.entry("API_KEY", 1)),
        List.copyOf(event.redactions().entrySet()));
  }

  @Test
  void failureTextIsRedacted() {
    var options =
        withTools(
            registryWithSecret(),
            tool(
                "throws",
                (args, ctx) -> {
                  throw new IllegalStateException("bad " + SECRET);
                }),
            tool(
                "unsupported",
                (args, ctx) -> {
                  throw new UnsupportedOperationException("bad " + SECRET);
                }));

    var run =
        run(options, calling(List.of(List.of(call("c1", "throws"), call("c2", "unsupported")))));

    assertEquals(
        List.of("tool 'throws' threw: bad " + MARKER, "tool 'unsupported' threw: bad " + MARKER),
        run.toolResults().stream().map(e -> e.result().output()).toList());
    assertEquals(
        run.toolResults().stream().map(e -> e.result().output()).toList(), run.toolMessages(1));
    assertEquals(
        List.of(
            new ToolOutcome.Threw("java.lang.IllegalStateException"),
            new ToolOutcome.Threw("java.lang.UnsupportedOperationException")),
        run.toolResults().stream().map(e -> e.result().outcome()).toList());
  }

  @Test
  void aSecretRegisteredMidSessionIsRedactedFromTheNextResult() {
    var options =
        withTools(
            new SecretRegistry(),
            tool(
                "register",
                (args, ctx) -> {
                  ctx.sessionContext().secrets().register("API_KEY", SECRET);
                  return ToolResult.success("registered");
                }),
            tool("reveal", (args, ctx) -> ToolResult.success(SECRET)));

    var run =
        run(
            options,
            calling(List.of(List.of(call("c1", "register")), List.of(call("c2", "reveal")))));

    var results = run.toolResults();
    assertEquals("registered", results.get(0).result().output());
    assertEquals(MARKER, results.get(1).result().output());
    assertEquals(Map.of("API_KEY", 1), results.get(1).redactions());
  }

  @Test
  void aMutatedResultIsRedactedBeforeItsEventAndTheHistory() {
    var options =
        withTools(registryWithSecret(), tool("clean", (args, ctx) -> ToolResult.success("clean")))
            .withHook(
                (PostToolUseHook)
                    (toolCall, result, ctx) -> new HookOutcome.MutateResult("mutated " + SECRET));

    var run = run(options, calling(List.of(List.of(call("c1", "clean")))));

    var results = run.toolResults();
    assertEquals(2, results.size());
    assertEquals(Map.of(), results.get(0).redactions());
    assertEquals("mutated " + MARKER, results.get(1).result().output());
    assertEquals(Map.of("API_KEY", 1), results.get(1).redactions());
    assertEquals(List.of("mutated " + MARKER), run.toolMessages(1));
  }

  @Test
  void theReadOnlyPresetsReadAndGrepReturnRedactedText(@TempDir Path root) throws IOException {
    Files.writeString(
        root.resolve("config.txt"), "user=alice\napi_key=" + SECRET + "\n", StandardCharsets.UTF_8);
    var model =
        calling(
            List.of(
                List.of(
                    new ToolCall("c1", "Read", Map.of("path", "config.txt")),
                    new ToolCall("c2", "Grep", Map.of("pattern", "api_key")))));
    var options = SessionPresets.readOnly(model, root).withSecretRegistry(registryWithSecret());

    var run = run(options, model);

    var outputs = run.toolResults().stream().map(e -> e.result().output()).toList();
    assertEquals(2, outputs.size());
    assertTrue(outputs.get(0).contains("api_key=" + MARKER), outputs.get(0));
    assertEquals("config.txt:2:api_key=" + MARKER + "\n", outputs.get(1));
    outputs.forEach(output -> assertFalse(output.contains(SECRET), output));
  }

  @Test
  void withoutARegistryOutputPassesThroughUnchanged() {
    var options =
        SessionOptions.newBuilder()
            .withTools(
                new ToolRegistry(
                    List.of(tool("reveal", (args, ctx) -> ToolResult.success("token=" + SECRET)))));

    var run = run(options, calling(List.of(List.of(call("c1", "reveal")))));

    assertEquals(0, run.secrets().size());
    var event = run.toolResults().getFirst();
    assertEquals("token=" + SECRET, event.result().output());
    assertEquals(Map.of(), event.redactions());
  }

  @Test
  void outputNoSecretMatchedReachesTheModelUnchangedEvenWhereUtf8CannotCarryIt() {
    var unpaired = "half a pair \uD800 here";
    var options =
        withTools(registryWithSecret(), tool("odd", (args, ctx) -> ToolResult.success(unpaired)));

    var run = run(options, calling(List.of(List.of(call("c1", "odd")))));

    assertEquals(unpaired, run.toolResults().getFirst().result().output());
    assertEquals(List.of(unpaired), run.toolMessages(1));
  }

  @Test
  void aCommandGrantRunInItsSessionReachesTheModelRedacted() {
    assumeTrue(Files.isExecutable(Path.of("/bin/bash")), "/bin/bash is required");
    var secrets = new SecretRegistry();
    var grant =
        CommandGrant.builder("/bin/bash")
            .withSecretRegistry(secrets)
            .withEnv("API_KEY", SECRET)
            .build();
    var options =
        SessionOptions.newBuilder()
            .withSecretRegistry(secrets)
            .withPermission(Permission.empty(PermissionMode.BYPASS_PERMISSIONS))
            .withTools(
                new ToolRegistry(
                    List.of(
                        ToolBinding.newBuilder(grant.toTool())
                            .withCategory(ToolCategory.EXECUTION)
                            .build())));
    var model =
        calling(
            List.of(
                List.of(
                    new ToolCall("c1", "bash", Map.of("args", List.of("-c", "echo $API_KEY"))))));

    var run = run(options, model);

    assertEquals(List.of("[exit 0]\n" + MARKER + "\n"), run.toolMessages(1));
  }

  @Test
  void errorsTheSessionReportsHaveEveryMessageInTheirChainRedacted() {
    var model =
        ScriptedModel.newBuilder()
            .withStreamTurn(
                ModelStreams.failing(
                    new TransientStreamException(
                        "overloaded", new IOException("upstream saw " + SECRET), "scripted")))
            .withStreamTurn(
                ModelStreams.failing(
                    new IllegalStateException(
                        "stream broke on " + SECRET, new IOException("cause " + SECRET))))
            .build();
    var options =
        SessionOptions.newBuilder()
            .withSecretRegistry(registryWithSecret())
            .withLimits(
                SessionLimits.newBuilder()
                    .withStreamRetryPolicy(
                        new StreamRetryPolicy(2, Backoff.fixed(Duration.ZERO), 0.0))
                    .build());

    var run = run(options, model);

    var retried = run.events().eventsOf(QueryEvent.TurnRetried.class);
    assertEquals(1, retried.size());
    assertEquals(
        List.of("overloaded", "upstream saw " + MARKER), messages(retried.getFirst().error()));
    var terminal = assertInstanceOf(ResultMessage.ErrorDuringExecution.class, run.result());
    assertEquals(
        List.of("stream broke on " + MARKER, "cause " + MARKER), messages(terminal.error()));
  }

  @Test
  void aFailedTurnShowsThePostModelTurnHookItsPartialTextAndEndsWithTheErrorRedacted() {
    var postModelTurn = new CopyOnWriteArrayList<String>();
    var model =
        ScriptedModel.newBuilder()
            .withStreamTurn(
                ModelStreams.failing(
                    new RuntimeException("stream broke on " + SECRET),
                    new ModelChunk.TextDelta("partial")))
            .build();
    var options =
        SessionOptions.newBuilder()
            .withSecretRegistry(registryWithSecret())
            .withHook(
                (PostModelTurnHook)
                    (response, ctx) -> {
                      postModelTurn.add(response.finishReason() + " " + response.content());
                      return new HookOutcome.Continue();
                    });

    var run = run(options, model);

    var failed = assertInstanceOf(ResultMessage.ErrorDuringExecution.class, run.result());
    assertEquals(List.of("ERROR partial"), postModelTurn);
    assertEquals("stream broke on " + MARKER, failed.error().message());
  }

  @Test
  void aProviderRefusalIsReportedRedacted() {
    var provider = new RefusingProvider();
    var options =
        SessionOptions.newBuilder()
            .withModel(ScriptedModel.newBuilder().build())
            .withSecretRegistry(registryWithSecret())
            .withExecutionProvider(provider)
            .build();

    ResultMessage terminal;
    try (var session = AgentSession.create(options)) {
      terminal = Await.value("the refused session's terminal", session.result());
    }

    var refusal = assertInstanceOf(ResultMessage.ErrorProviderUnavailable.class, terminal);
    assertEquals("startup printed " + MARKER, refusal.reason());
    assertEquals(List.of("cause " + MARKER), messages(refusal.cause()));
  }

  private static List<String> messages(SerializedError error) {
    var messages = new ArrayList<String>();
    for (var e = error; e != null; e = e.cause()) {
      messages.add(e.message());
    }
    return messages;
  }

  /** Refuses every session with the secret in its reason and in its cause's message. */
  private static final class RefusingProvider implements ExecutionProvider {

    @Override
    public Set<Runtime> supportedRuntimes() {
      return Set.of();
    }

    @Override
    public ExecutionResult execute(
        SessionContext session, ExecutionRequest request, CancellationToken cancellation) {
      throw new UnsupportedOperationException("a refused session never executes");
    }

    @Override
    public SessionStartOutcome onSessionStart(SessionContext ctx) {
      return SessionStartOutcome.refuse(
          "startup printed " + SECRET, new IOException("cause " + SECRET));
    }
  }
}
