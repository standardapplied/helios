/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.model.ToolCall;
import io.opentelemetry.context.Context;

/**
 * Spans for loop tests that start nothing: each step runs in the context current where it starts.
 */
final class NoSpans implements StepSpans {

  static final StepSpans INSTANCE = new NoSpans();

  private NoSpans() {}

  @Override
  public Context modelAttempt(SessionState state) {
    return Context.current();
  }

  @Override
  public Context toolCall(SessionState state, ToolCall call) {
    return Context.current();
  }
}
