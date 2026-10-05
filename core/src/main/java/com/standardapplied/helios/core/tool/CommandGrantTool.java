/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.tool;

import com.standardapplied.helios.core.tool.CommandGrant.InvocationResult;
import com.standardapplied.helios.core.tool.CommandGrant.RejectedException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The model-facing side of a {@link CommandGrant}: one {@code args} array parameter in, the exit
 * status and redacted output rendered as text out.
 */
record CommandGrantTool(CommandGrant grant, String description, boolean stderrToModel) {

  Tool toTool() {
    return Tool.newBuilder()
        .withName(grant.name())
        .withDescription(description)
        .withParameter(
            ToolParameter.newBuilder()
                .withName("args")
                .withType(ParameterType.ARRAY)
                .withDescription(
                    "Arguments passed to %s (excluding the binary itself)"
                        .formatted(grant.binaryPath().getFileName()))
                .withRequired(true)
                .withItems(ToolParameter.newBuilder().withType(ParameterType.STRING).build())
                .build())
        .withExecutor(this::execute)
        .build();
  }

  private ToolResult execute(Map<String, Object> args, ToolContext ctx) {
    ctx.cancellation().throwIfCancelled();
    var raw = args.get("args");
    if (!(raw instanceof List<?> list)) {
      return ToolResult.failure("Parameter 'args' is required and must be an array of strings");
    }
    var argv = new ArrayList<String>(list.size());
    for (var entry : list) {
      if (!(entry instanceof String s)) {
        return ToolResult.failure("Every entry in 'args' must be a string");
      }
      argv.add(s);
    }
    return invoke(argv);
  }

  private ToolResult invoke(List<String> argv) {
    try {
      var result = grant.invoke(argv);
      return ToolResult.success(render(result), result);
    } catch (RejectedException e) {
      return ToolResult.failure(e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return ToolResult.failure("Interrupted while invoking " + grant.name());
    } catch (IOException e) {
      return ToolResult.failure("I/O error invoking " + grant.name() + ": " + e.getMessage());
    }
  }

  private String render(InvocationResult result) {
    var sb = new StringBuilder();
    sb.append("[exit ").append(result.exitCode());
    if (result.timedOut()) {
      sb.append(" TIMEOUT");
    }
    if (result.truncated()) {
      sb.append(" TRUNCATED");
    }
    sb.append("]\n");
    sb.append(result.stdout());
    if (stderrToModel && !result.stderr().isEmpty()) {
      sb.append("\n[stderr]\n").append(result.stderr());
    }
    return sb.toString();
  }
}
