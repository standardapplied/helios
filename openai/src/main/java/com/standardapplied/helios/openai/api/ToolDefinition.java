/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.openai.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * Function tool definition for the OpenAI Responses API.
 *
 * @param type always "function"
 * @param name function name
 * @param description function description
 * @param parameters JSON Schema for the function parameters
 * @param strict whether to enforce strict schema adherence
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolDefinition(
    String type, String name, String description, Map<String, Object> parameters, Boolean strict) {

  public static ToolDefinition function(
      String name, String description, Map<String, Object> parameters) {
    return new ToolDefinition("function", name, description, parameters, null);
  }
}
