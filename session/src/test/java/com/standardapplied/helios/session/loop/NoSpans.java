/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.model.ToolCall;
import io.opentelemetry.context.Context;
import java.util.OptionalLong;
import java.util.function.Consumer;

/**
 * Spans for loop tests that start nothing: each step runs in the context current where it starts.
 */
final class NoSpans implements StepSpans {

  static final StepSpans INSTANCE = new NoSpans(step -> {});

  private final Consumer<String> steps;

  private NoSpans(Consumer<String> steps) {
    this.steps = steps;
  }

  /**
   * Spans that start nothing and describe to {@code steps} each attempt's served model, {@code
   * served <model or null>}, and first output, {@code first chunk <millis>, first text <millis>}.
   */
  static StepSpans recordingTo(Consumer<String> steps) {
    return new NoSpans(steps);
  }

  @Override
  public Context modelAttempt(SessionState state) {
    return Context.current();
  }

  @Override
  public void servedModel(SessionState state, String model) {
    steps.accept("served " + model);
  }

  @Override
  public void firstOutput(
      SessionState state, OptionalLong firstChunkMillis, OptionalLong firstTextMillis) {
    steps.accept("first chunk " + firstChunkMillis + ", first text " + firstTextMillis);
  }

  @Override
  public Context toolCall(SessionState state, ToolCall call) {
    return Context.current();
  }
}
