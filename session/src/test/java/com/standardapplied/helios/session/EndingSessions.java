/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolExecutor;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolRegistry;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Sessions and models for the tests of how a session ends: the session {@link #SID}, the short
 * teardown grace under test and one beyond the hang guard, models built from a stream function,
 * hand-made subscriptions and tools.
 */
final class EndingSessions {

  static final String SID = "sess-final";
  static final Duration SHORT_GRACE = Duration.ofMillis(50);
  static final Duration NOT_REACHED = Duration.ofMinutes(10);

  private EndingSessions() {}

  static SessionOptions.Builder options(Model model) {
    return SessionOptions.newBuilder().withModel(model).withSessionId(SID);
  }

  static SessionOptions.Builder withProvider(RecordingProvider provider) {
    return withProvider(provider, ScriptedModel.newBuilder().withTextTurn("done").build());
  }

  static SessionOptions.Builder withProvider(RecordingProvider provider, Model model) {
    return options(model).withExecutionProvider(provider);
  }

  /** A session whose model calls {@code tool} once, then answers. */
  static SessionOptions.Builder callingTool(RecordingProvider provider, ToolBinding tool) {
    var call = new ToolCall("c-" + tool.name(), tool.name(), Map.of());
    var model = ScriptedModel.newBuilder().withToolCallsTurn(call).withTextTurn("done").build();
    return withProvider(provider, model).withTools(new ToolRegistry(List.of(tool)));
  }

  static SessionLimits grace() {
    return SessionLimits.newBuilder().withTeardownGrace(SHORT_GRACE).build();
  }

  static SessionLimits slowGrace() {
    return SessionLimits.newBuilder().withTeardownGrace(NOT_REACHED).build();
  }

  static ToolBinding binding(String name, ToolExecutor executor) {
    return ToolBinding.newBuilder(
            Tool.newBuilder().withName(name).withDescription(name).withExecutor(executor).build())
        .withCategory(ToolCategory.READ)
        .build();
  }

  /** What the result of a session over {@code model}, sent one message, fails with. */
  static Throwable failureOf(Model model) {
    try (var session = AgentSession.create(options(model).build())) {
      session.send("go");
      return Await.failure("the result to fail", session.result());
    }
  }

  static Flow.Subscription subscription(Runnable request, Runnable cancel) {
    return new Flow.Subscription() {
      @Override
      public void request(long n) {
        request.run();
      }

      @Override
      public void cancel() {
        cancel.run();
      }
    };
  }

  /** A model whose stream for each call is what {@code stream} returns for the call's token. */
  static Model model(Function<CancellationToken, Flow.Publisher<ModelChunk>> stream) {
    return new Model() {
      @Override
      public Flow.Publisher<ModelChunk> chatStream(
          List<Message> messages, List<Tool> tools, CancellationToken cancellation) {
        return stream.apply(cancellation);
      }

      @Override
      public String id() {
        return "scripted-stream";
      }

      @Override
      public String provider() {
        return "test";
      }
    };
  }

  static void assertReported(List<LogRecord> logged, String warning) {
    var warnings =
        logged.stream()
            .filter(record -> record.getLevel() == Level.WARNING)
            .map(LogRecord::getMessage)
            .toList();
    assertTrue(warnings.contains(warning), () -> "logged warnings: " + warnings);
  }
}
