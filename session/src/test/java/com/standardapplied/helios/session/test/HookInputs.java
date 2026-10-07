/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.test;

import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.test.MockModel;
import com.standardapplied.helios.session.hooks.DefaultHookContext;
import com.standardapplied.helios.session.hooks.HookContext;
import java.util.Map;

/**
 * What a test hands a hook that observes tool calls and gates the stop: a hook context on turn 0 of
 * session {@code sess}, a tool call by name and a final text response.
 */
public final class HookInputs {

  private static final MockModel MODEL = new MockModel("");

  private HookInputs() {}

  /**
   * A context for session {@code sess}, turn 0, with a fresh cancellation token.
   *
   * @return the context
   */
  public static HookContext context() {
    return new DefaultHookContext("sess", 0, new CancellationToken(), MODEL);
  }

  /**
   * A call of the named tool with no arguments, its id {@code c-<name>}.
   *
   * @param name the tool's name
   * @return the call
   */
  public static ToolCall call(String name) {
    return new ToolCall("c-" + name, name, Map.of());
  }

  /**
   * A response that ends the turn with the text {@code done} and no tool call.
   *
   * @return the response
   */
  public static Response<Void> stopResponse() {
    return Response.newBuilder().withContent("done").build();
  }
}
