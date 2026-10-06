/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.tool.Tool;
import java.util.List;
import java.util.Map;

/**
 * Builds a provider's request for one turn.
 *
 * @param <R> the provider's request type
 */
@FunctionalInterface
public interface RequestFactory<R> {

  /**
   * The request for a turn of {@code messages}, offering {@code tools}.
   *
   * @param messages the conversation so far
   * @param tools the tools the model may call
   * @param outputSchema the JSON schema the answer must match, or {@code null} for free text
   * @return the provider's request
   */
  R build(List<Message> messages, List<Tool> tools, Map<String, Object> outputSchema);
}
