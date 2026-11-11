/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.model.ToolCall;
import io.opentelemetry.context.Context;
import java.util.function.Consumer;

/**
 * Spans for loop tests that start nothing: each step runs in the context current where it starts.
 */
final class NoSpans implements StepSpans {

  static final StepSpans INSTANCE = new NoSpans(model -> {});

  private final Consumer<String> served;

  private NoSpans(Consumer<String> served) {
    this.served = served;
  }

  /**
   * Spans that start nothing and pass each attempt's served model, or {@code null}, to {@code
   * served}.
   */
  static StepSpans servedTo(Consumer<String> served) {
    return new NoSpans(served);
  }

  @Override
  public Context modelAttempt(SessionState state) {
    return Context.current();
  }

  @Override
  public void servedModel(SessionState state, String model) {
    served.accept(model);
  }

  @Override
  public Context toolCall(SessionState state, ToolCall call) {
    return Context.current();
  }
}
