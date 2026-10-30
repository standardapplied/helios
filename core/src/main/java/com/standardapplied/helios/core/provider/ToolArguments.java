/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import com.standardapplied.helios.core.common.JsonSnapshot;
import com.standardapplied.helios.core.model.ProviderException;
import com.standardapplied.helios.core.schema.StructuredContentParser;
import java.util.Map;

/**
 * The arguments a model streamed for a tool call, read as the JSON object every tool takes and
 * snapshotted as {@link JsonSnapshot} defines it. A call whose arguments are anything else is never
 * dispatched: reading them fails with the provider's exception, naming the tool, which ends the
 * stream in an error.
 */
public final class ToolArguments {

  private ToolArguments() {}

  /**
   * The JSON object {@code json} as a {@link JsonSnapshot}; empty when no arguments streamed. A
   * {@code null} value inside the object stays {@code null}.
   *
   * @param toolName the tool the arguments are for
   * @param json the arguments as streamed
   * @param reader the provider's JSON reader
   * @param failure creates the provider's exception
   * @return the arguments
   * @throws ProviderException if {@code json} is not a JSON object, or is one {@link JsonSnapshot}
   *     rejects
   */
  public static Map<String, Object> read(
      String toolName,
      String json,
      StructuredContentParser.JsonAdapter.Reader reader,
      ProviderFailure failure) {
    if (json.isEmpty()) {
      return Map.of();
    }
    Map<String, Object> arguments;
    try {
      arguments = reader.read(json);
    } catch (Exception e) {
      throw notAnObject(toolName, e.getMessage(), failure, e);
    }
    if (arguments == null) {
      throw notAnObject(toolName, "null", failure, null);
    }
    try {
      return JsonSnapshot.ofObject("arguments", arguments);
    } catch (IllegalArgumentException e) {
      throw failure.create(
          "Arguments of tool call '" + toolName + "' are rejected: " + e.getMessage(), 0, e);
    }
  }

  private static ProviderException notAnObject(
      String toolName, String reason, ProviderFailure failure, Exception cause) {
    return failure.create(
        "Arguments of tool call '" + toolName + "' are not a JSON object: " + reason, 0, cause);
  }
}
