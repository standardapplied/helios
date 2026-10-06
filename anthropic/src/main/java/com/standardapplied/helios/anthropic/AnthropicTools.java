/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.anthropic.api.ToolChoiceConfig;
import com.standardapplied.helios.anthropic.api.ToolDefinition;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.ToolChoice;
import com.standardapplied.helios.core.tool.Tool;
import java.util.ArrayList;
import java.util.List;

/**
 * The tools of a request: the caller's client tools, then the Anthropic server tools the
 * configuration enables, which keep their documented {@code {type, name}} shape. A client tool may
 * not take the name of an enabled server tool.
 *
 * @param client the client tool definitions, or {@code null} when the caller passed none
 * @param server the enabled server tool definitions
 */
record AnthropicTools(List<ToolDefinition> client, List<ToolDefinition> server) {

  /** The tools of a request offering {@code tools} under {@code config}. */
  static AnthropicTools of(List<Tool> tools, ModelConfig config) {
    var client =
        tools == null || tools.isEmpty()
            ? null
            : tools.stream()
                .map(
                    tool ->
                        new ToolDefinition(
                            null,
                            tool.name(),
                            tool.description(),
                            tool.parametersAsJsonSchema(),
                            null))
                .toList();
    var server = new ArrayList<ToolDefinition>();
    if (config.webSearch()) {
      server.add(ToolDefinition.webSearch());
    }
    if (config.webFetch()) {
      server.add(ToolDefinition.webFetch());
    }
    rejectCollisions(client, server);
    return new AnthropicTools(client, List.copyOf(server));
  }

  /** Whether {@code toolChoice} forces a tool call: {@code any} or a required tool. */
  static boolean forces(ToolChoice toolChoice) {
    return toolChoice instanceof ToolChoice.Any || toolChoice instanceof ToolChoice.Required;
  }

  /** The wire tool choice for {@code toolChoice}, or {@code null} to send none. */
  static ToolChoiceConfig choice(ToolChoice toolChoice) {
    return switch (toolChoice) {
      case null -> null;
      case ToolChoice.Auto auto -> ToolChoiceConfig.auto();
      case ToolChoice.Any any -> ToolChoiceConfig.any();
      case ToolChoice.None none -> null;
      case ToolChoice.Required required -> {
        if (required.allowedTools().size() > 1) {
          throw new IllegalStateException(
              "Claude tool choice supports only a single tool name, got: "
                  + required.allowedTools());
        }
        yield ToolChoiceConfig.tool(required.allowedTools().iterator().next());
      }
    };
  }

  /** The client tools followed by the server tools. */
  List<ToolDefinition> all() {
    return followedByServerTools(client);
  }

  /** {@code clientTools} followed by the server tools; {@code clientTools} alone without any. */
  List<ToolDefinition> followedByServerTools(List<ToolDefinition> clientTools) {
    if (server.isEmpty()) {
      return clientTools;
    }
    var combined = new ArrayList<ToolDefinition>();
    if (clientTools != null) {
      combined.addAll(clientTools);
    }
    combined.addAll(server);
    return List.copyOf(combined);
  }

  private static void rejectCollisions(List<ToolDefinition> client, List<ToolDefinition> server) {
    if (client == null) {
      return;
    }
    for (var serverTool : server) {
      for (var clientTool : client) {
        if (serverTool.name().equals(clientTool.name())) {
          throw new IllegalArgumentException(
              "Client tool name '"
                  + clientTool.name()
                  + "' collides with the enabled Anthropic server tool of the same name;"
                  + " rename the client tool or disable the toggle");
        }
      }
    }
  }
}
