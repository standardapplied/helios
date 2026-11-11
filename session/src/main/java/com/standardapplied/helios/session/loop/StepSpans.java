/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.model.ToolCall;
import io.opentelemetry.context.Context;

/**
 * The spans a run's model attempts and tool calls run under. The loop makes each step's {@link
 * Context} current while the step runs, on its own thread and on every thread it starts for the
 * step, so spans the model client or the tool records nest under the step's span. The session's
 * events end each span; the session's implementation owns every span name and attribute.
 */
public interface StepSpans {

  /**
   * Start the span of {@code state}'s next model attempt, ended by the attempt's {@link
   * com.standardapplied.helios.session.QueryEvent.ModelCallEnded}.
   *
   * @return the context the attempt's stream is opened and read in
   */
  Context modelAttempt(SessionState state);

  /**
   * Record the model the provider reported for {@code state}'s model attempt in progress, or {@code
   * null} when it reported none.
   */
  void servedModel(SessionState state, String model);

  /**
   * The context {@code call}'s tool runs in: the span its {@link
   * com.standardapplied.helios.session.QueryEvent.ToolUse} started.
   */
  Context toolCall(SessionState state, ToolCall call);
}
