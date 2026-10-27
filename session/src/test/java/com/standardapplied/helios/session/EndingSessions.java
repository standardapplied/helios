/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.Uninterruptible;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolExecutor;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.PreStopHook;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Sessions and models for the tests of how a session ends: the session {@link #SID}, the short
 * teardown grace under test and one beyond the hang guard, models built from a stream supplier,
 * hand-made streams and tools.
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
    return withProvider(provider, model).withTools(List.of(tool));
  }

  static SessionLimits grace() {
    return SessionLimits.newBuilder().withStopGrace(SHORT_GRACE).build();
  }

  static SessionLimits slowGrace() {
    return SessionLimits.newBuilder().withStopGrace(NOT_REACHED).build();
  }

  static ToolBinding binding(String name, ToolExecutor executor) {
    return ToolBinding.newBuilder(
            Tool.newBuilder().withName(name).withDescription(name).withExecutor(executor).build())
        .withCategory(ToolCategory.READ)
        .build();
  }

  /** What the result of a session over {@code model}, sent one message, fails with. */
  static Throwable failureOf(Model model) {
    return failureOf(options(model));
  }

  /** What the result of a session built from {@code options}, sent one message, fails with. */
  static Throwable failureOf(SessionOptions.Builder options) {
    try (var session = AgentSession.open(options.build())) {
      var future = session.send("go");
      return Await.failure("the result to fail", future);
    }
  }

  /** A model whose stream for each call is what {@code stream} supplies. */
  static Model model(Supplier<CloseableIterator<ModelChunk>> stream) {
    return new Model() {
      @Override
      public CloseableIterator<ModelChunk> chatStream(List<Message> messages, List<Tool> tools) {
        return stream.get();
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

  /**
   * A stream whose reads run {@code read}, which yields whether a chunk follows, and whose close
   * runs {@code close}; it yields no chunk.
   */
  static CloseableIterator<ModelChunk> stream(BooleanSupplier read, Runnable close) {
    return new CloseableIterator<>() {
      @Override
      public boolean hasNext() {
        return read.getAsBoolean();
      }

      @Override
      public ModelChunk next() {
        throw new NoSuchElementException("this stream yields no chunk");
      }

      @Override
      public void close() {
        close.run();
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

  /**
   * Options whose run, once it decided to end, holds its end until {@code release} opens: a
   * callback of its token's cancellation opens {@code ending} and waits.
   */
  static SessionOptions.Builder holdingItsEnd(
      SessionOptions.Builder options, CountDownLatch ending, CountDownLatch release) {
    var held = new AtomicBoolean();
    PreStopHook holding =
        (response, ctx) -> {
          if (!held.getAndSet(true)) {
            ctx.cancellation()
                .onCancel(
                    () -> {
                      ending.countDown();
                      Uninterruptible.await(release);
                    });
          }
          return HookOutcome.cont();
        };
    return options
        .withHook(holding)
        .withLimits(SessionLimits.newBuilder().withStopGrace(NOT_REACHED).build());
  }
}
