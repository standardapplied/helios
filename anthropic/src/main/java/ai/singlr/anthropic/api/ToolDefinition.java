/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.anthropic.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import java.util.Objects;

/**
 * Tool definition for the Claude Messages API.
 *
 * <p>Annotating a tool with {@link #cacheControl()} marks the prefix up to and including this
 * tool's array slot as a prompt-cache breakpoint. The Anthropic best-practice placement is on the
 * last tool of the array so the entire tools section caches as a single prefix.
 *
 * @param name the tool name
 * @param description description of what the tool does
 * @param inputSchema JSON Schema for tool parameters
 * @param cacheControl optional prompt-cache breakpoint anchored to this tool
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolDefinition(
    String name,
    String description,
    @JsonProperty("input_schema") Map<String, Object> inputSchema,
    @JsonProperty("cache_control") CacheControl cacheControl) {

  /**
   * Convenience constructor without a cache breakpoint. Preserves the prior call sites that predate
   * prompt caching.
   *
   * @param name the tool name
   * @param description description of what the tool does
   * @param inputSchema JSON Schema for tool parameters
   */
  public ToolDefinition(String name, String description, Map<String, Object> inputSchema) {
    this(name, description, inputSchema, null);
  }

  /**
   * Return a copy of this definition carrying the given prompt-cache breakpoint.
   *
   * @param cacheControl the breakpoint; non-null
   * @return a copy with {@code cache_control} set
   */
  public ToolDefinition withCacheControl(CacheControl cacheControl) {
    Objects.requireNonNull(cacheControl, "cacheControl must not be null");
    return new ToolDefinition(name, description, inputSchema, cacheControl);
  }
}
