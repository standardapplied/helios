/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * Tool definition for the Interactions API.
 *
 * @param type the tool type ("function", "google_search", "code_execution", etc.)
 * @param name function name (for type "function")
 * @param description function description (for type "function")
 * @param parameters JSON Schema for function parameters (for type "function")
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolDefinition(
    String type, String name, String description, Map<String, Object> parameters) {

  public static ToolDefinition function(
      String name, String description, Map<String, Object> parameters) {
    return new ToolDefinition("function", name, description, parameters);
  }

  public static ToolDefinition googleSearch() {
    return new ToolDefinition("google_search", null, null, null);
  }

  public static ToolDefinition codeExecution() {
    return new ToolDefinition("code_execution", null, null, null);
  }

  public static ToolDefinition urlContext() {
    return new ToolDefinition("url_context", null, null, null);
  }
}
