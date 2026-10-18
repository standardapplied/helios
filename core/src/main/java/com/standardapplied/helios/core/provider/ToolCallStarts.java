/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.model.ProviderException;
import com.standardapplied.helios.core.model.StreamEvent;

/**
 * The start of a tool call a model streamed, accepted only with the id its result is matched by and
 * the name of its tool. A call without either is never dispatched: its start fails with the
 * provider's exception, which ends the stream in an error, the same for {@code chat} and {@code
 * chatStream}.
 */
public final class ToolCallStarts {

  private ToolCallStarts() {}

  /**
   * The start of tool call {@code id} of tool {@code name}.
   *
   * @param id the call's id
   * @param name the tool's name
   * @param failure creates the provider's exception
   * @return the start event
   * @throws ProviderException if {@code name} or {@code id} is null or blank
   */
  public static StreamEvent.ToolCallStart of(String id, String name, ProviderFailure failure) {
    if (Strings.isBlank(name)) {
      throw failure.create("A tool call streamed without a tool name", 0, null);
    }
    if (Strings.isBlank(id)) {
      throw failure.create("Tool call '" + name + "' streamed without an id", 0, null);
    }
    return new StreamEvent.ToolCallStart(id, name);
  }
}
