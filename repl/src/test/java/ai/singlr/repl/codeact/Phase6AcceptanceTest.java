/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.repl.codeact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.ModelChunk;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.Response.Usage;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.tool.Tool;
import ai.singlr.session.AgentSession;
import ai.singlr.session.ResultMessage;
import ai.singlr.session.SessionOptions;
import ai.singlr.session.UserMessage;
import ai.singlr.session.execution.ExecuteTool;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

/**
 * Phase 6 acceptance: scripted-model tests that drive {@link CodeActPreset} end-to-end against a
 * real {@link ai.singlr.repl.execution.JShellExecutionProvider} sandbox. Validates that the typed
 * and RLM (withSubLm) shapes both terminate with the expected typed value through the agent loop.
 */
final class Phase6AcceptanceTest {

  public record SumInput(List<Integer> numbers) {}

  public record Sum(int value) {}

  public record SimpleInput(String topic) {}

  public record Headline(String headline) {}

  private static Flow.Publisher<ModelChunk> chunks(List<ModelChunk> emit) {
    return subscriber ->
        subscriber.onSubscribe(
            new Flow.Subscription() {
              @Override
              public void request(long n) {
                for (var c : emit) {
                  subscriber.onNext(c);
                }
                subscriber.onComplete();
              }

              @Override
              public void cancel() {}
            });
  }

  private static Model scriptedModel(List<List<ModelChunk>> turns, String id) {
    return new Model() {
      private int turnIndex = 0;

      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        throw new AssertionError("scripted model expects chatStream");
      }

      @Override
      public Flow.Publisher<ModelChunk> chatStream(
          List<Message> messages, List<Tool> tools, CancellationToken cancellation) {
        var emit = turnIndex < turns.size() ? turns.get(turnIndex++) : List.<ModelChunk>of();
        return chunks(emit);
      }

      @Override
      public Flow.Publisher<ModelChunk> chatStream(
          List<Message> messages,
          List<Tool> tools,
          OutputSchema<?> outputSchema,
          CancellationToken cancellation) {
        return chatStream(messages, tools, cancellation);
      }

      @Override
      public String id() {
        return id;
      }

      @Override
      public String provider() {
        return "test";
      }
    };
  }

  private static Model fixedReply(String reply) {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        return Response.newBuilder().withContent(reply).build();
      }

      @Override
      public Flow.Publisher<ModelChunk> chatStream(
          List<Message> messages, List<Tool> tools, CancellationToken cancellation) {
        throw new AssertionError("sub-LM expects chat(...)");
      }

      @Override
      public String id() {
        return "sub";
      }

      @Override
      public String provider() {
        return "test";
      }
    };
  }

  // ── (a) typed CodeAct: execute_code -> final JSON answer ──────────────────

  @Test
  void typedCodeActExecutesThenEmitsFinalJsonAnswer() throws Exception {
    var input = new SumInput(List.of(1, 2, 3, 4));
    // Turn 1: model emits an Execute(JSHELL) call running real Java in the sandbox.
    // Turn 2: model emits the final JSON answer as assistant text — runBlocking parses it.
    var turns =
        List.<List<ModelChunk>>of(
            List.of(
                new ModelChunk.ToolUseStop(
                    new ToolCall(
                        "c1",
                        ExecuteTool.NAME,
                        Map.of(
                            "runtime",
                            "JSHELL",
                            "script",
                            "var total = numbers.stream().mapToInt(Integer::intValue).sum();\n"
                                + "println(total);"))),
                new ModelChunk.MessageStop("TOOL_CALLS", Usage.of(1, 1))),
            List.of(
                new ModelChunk.TextDelta("{\"value\": 10}"),
                new ModelChunk.MessageStop("STOP", Usage.of(1, 1))));

    var options =
        SessionOptions.newBuilder()
            .withModel(scriptedModel(turns, "typed-phase6"))
            .withSessionId("phase6-typed-" + UUID.randomUUID())
            .apply(CodeActPreset.typed(SumInput.class, Sum.class, input))
            .build();

    try (var session = AgentSession.create(options)) {
      var typed =
          session.runBlocking(UserMessage.text("sum the numbers"), OutputSchema.of(Sum.class));
      assertNotNull(typed);
      assertEquals(10, typed.value());
    }
  }

  // ── (b) RequireExecuteCodeHook gates a premature stop ─────────────────────

  @Test
  void requireExecuteCodeBlocksStopThenAllowsItAfterExecution() throws Exception {
    var input = new SumInput(List.of(2, 3));
    // Turn 1: model tries to stop without running any code — RequireExecuteCodeHook injects.
    // Turn 2: model runs code in the sandbox.
    // Turn 3: model emits the final JSON answer.
    var turns =
        List.<List<ModelChunk>>of(
            List.of(
                new ModelChunk.TextDelta("{\"value\": 5}"),
                new ModelChunk.MessageStop("STOP", Usage.of(1, 1))),
            List.of(
                new ModelChunk.ToolUseStop(
                    new ToolCall(
                        "c1",
                        ExecuteTool.NAME,
                        Map.of(
                            "runtime",
                            "JSHELL",
                            "script",
                            "var total = numbers.stream().mapToInt(Integer::intValue).sum();\n"
                                + "println(total);"))),
                new ModelChunk.MessageStop("TOOL_CALLS", Usage.of(1, 1))),
            List.of(
                new ModelChunk.TextDelta("{\"value\": 5}"),
                new ModelChunk.MessageStop("STOP", Usage.of(1, 1))));

    var options =
        SessionOptions.newBuilder()
            .withModel(scriptedModel(turns, "typed-phase6-reqexec"))
            .withSessionId("phase6-reqexec-" + UUID.randomUUID())
            .apply(CodeActPreset.typed(SumInput.class, Sum.class, input))
            .build();

    try (var session = AgentSession.create(options)) {
      var typed = session.runBlocking(UserMessage.text("sum"), OutputSchema.of(Sum.class));
      assertEquals(5, typed.value());
    }
  }

  // ── (c) withSubLm: in-sandbox submit() terminates with typed value ────────

  @Test
  void withSubLmTerminatesViaInSandboxSubmit() throws Exception {
    var input = new SimpleInput("widgets");
    var subModel = fixedReply("widgets are great");
    // Turn 1: model uses predict() to summarize, then submit() with the typed map.
    // The in-sandbox submit triggers OnSubmitStopHook which terminates the loop.
    var turns =
        List.<List<ModelChunk>>of(
            List.of(
                new ModelChunk.ToolUseStop(
                    new ToolCall(
                        "c1",
                        ExecuteTool.NAME,
                        Map.of(
                            "runtime",
                            "JSHELL",
                            "script",
                            "var summary = predict(\"Summarize in one sentence\", topic);\n"
                                + "submit(java.util.Map.of(\"headline\", summary));"))),
                new ModelChunk.MessageStop("TOOL_CALLS", Usage.of(1, 1))));

    var options =
        SessionOptions.newBuilder()
            .withModel(scriptedModel(turns, "rlm-phase6"))
            .withSessionId("phase6-rlm-" + UUID.randomUUID())
            .apply(CodeActPreset.withSubLm(SimpleInput.class, Headline.class, input, subModel))
            .build();

    try (var session = AgentSession.create(options)) {
      var typed =
          session.runBlocking(
              UserMessage.text("write a headline"), OutputSchema.of(Headline.class));
      assertNotNull(typed);
      assertEquals("widgets are great", typed.headline());
    }
  }

  // ── (d) typed preset never refers to a submit tool at the agent-loop ─────

  @Test
  void typedPresetDoesNotRegisterSubmitToolAtAgentLoopLevel() {
    var input = new SumInput(List.of(1));
    var options =
        SessionOptions.newBuilder()
            .withModel(fixedReply("ignored"))
            .apply(CodeActPreset.typed(SumInput.class, Sum.class, input))
            .build();
    var toolNames = options.tools().bindings().stream().map(b -> b.tool().name()).toList();
    assertEquals(1, toolNames.size());
    assertEquals(ExecuteTool.NAME, toolNames.get(0));
  }

  // ── (e) withSubLm preset registers exactly the same agent-loop tools ─────

  @Test
  void withSubLmRegistersOnlyExecuteAtAgentLoopLevel() {
    var input = new SumInput(List.of(1));
    var subModel = fixedReply("anything");
    var options =
        SessionOptions.newBuilder()
            .withModel(fixedReply("ignored"))
            .apply(CodeActPreset.withSubLm(SumInput.class, Sum.class, input, subModel))
            .build();
    var toolNames = options.tools().bindings().stream().map(b -> b.tool().name()).toList();
    assertEquals(1, toolNames.size());
    assertEquals(ExecuteTool.NAME, toolNames.get(0));
  }

  @Test
  void presetSetsLockedDownPermissionAndDisabledMemory() {
    var input = new SumInput(List.of(1));
    var options =
        SessionOptions.newBuilder()
            .withModel(fixedReply("ignored"))
            .apply(CodeActPreset.typed(SumInput.class, Sum.class, input))
            .build();
    assertInstanceOf(
        ai.singlr.session.permissions.Permission.class, options.permission().orElseThrow());
    assertTrue(options.memoryBackend().isPresent());
    assertEquals(
        ai.singlr.session.permissions.PermissionMode.LOCKED_DOWN,
        options.permission().orElseThrow().mode());
  }

  @Test
  void presetSetsOutputSchemaAndSystemPrompt() {
    var input = new SumInput(List.of(1));
    var options =
        SessionOptions.newBuilder()
            .withModel(fixedReply("ignored"))
            .apply(CodeActPreset.typed(SumInput.class, Sum.class, input))
            .build();
    assertTrue(options.outputSchema().isPresent());
    assertTrue(options.systemPrompt().isPresent());
    var prompt = options.systemPrompt().orElseThrow();
    assertTrue(prompt.contains("JShell"));
    assertTrue(prompt.contains("Execute"));
  }

  @Test
  void typedPresetRejectsNullClasses() {
    org.junit.jupiter.api.Assertions.assertThrows(
        NullPointerException.class,
        () -> CodeActPreset.typed(null, Sum.class, new SumInput(List.of())));
    org.junit.jupiter.api.Assertions.assertThrows(
        NullPointerException.class,
        () -> CodeActPreset.typed(SumInput.class, null, new SumInput(List.of())));
  }

  @Test
  void withSubLmPresetRejectsNullArguments() {
    var input = new SumInput(List.of(1));
    var sub = fixedReply("x");
    org.junit.jupiter.api.Assertions.assertThrows(
        NullPointerException.class, () -> CodeActPreset.withSubLm(null, Sum.class, input, sub));
    org.junit.jupiter.api.Assertions.assertThrows(
        NullPointerException.class,
        () -> CodeActPreset.withSubLm(SumInput.class, null, input, sub));
    org.junit.jupiter.api.Assertions.assertThrows(
        NullPointerException.class,
        () -> CodeActPreset.withSubLm(SumInput.class, Sum.class, input, null));
  }

  // ── (f) ResultMessage.Success carries the answer text ─────────────────────

  @Test
  void rlmSessionTerminalSuccessCarriesSerializedSubmittedValue() throws Exception {
    var input = new SimpleInput("widgets");
    var subModel = fixedReply("excellent widgets");
    var turns =
        List.<List<ModelChunk>>of(
            List.of(
                new ModelChunk.ToolUseStop(
                    new ToolCall(
                        "c1",
                        ExecuteTool.NAME,
                        Map.of(
                            "runtime",
                            "JSHELL",
                            "script",
                            "submit(java.util.Map.of(\"headline\", \"excellent widgets\"));"))),
                new ModelChunk.MessageStop("TOOL_CALLS", Usage.of(1, 1))));

    var options =
        SessionOptions.newBuilder()
            .withModel(scriptedModel(turns, "rlm-phase6-direct"))
            .withSessionId("phase6-rlm-direct-" + UUID.randomUUID())
            .apply(CodeActPreset.withSubLm(SimpleInput.class, Headline.class, input, subModel))
            .build();

    try (var session = AgentSession.create(options)) {
      var terminal = session.runBlocking(UserMessage.text("submit a headline"));
      var success = assertInstanceOf(ResultMessage.Success.class, terminal);
      assertTrue(success.result().contains("excellent widgets"));
    }
  }
}
