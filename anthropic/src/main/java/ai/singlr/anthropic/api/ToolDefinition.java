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
 * Tool definition for the Claude Messages API — a client tool (name + description + input schema)
 * or an Anthropic server tool ({@code type} + {@code name}, executed on Anthropic's
 * infrastructure).
 *
 * <p>Annotating a tool with {@link #cacheControl()} marks the prefix up to and including this
 * tool's array slot as a prompt-cache breakpoint. The Anthropic best-practice placement is on the
 * last tool of the array so the entire tools section caches as a single prefix.
 *
 * @param type the server-tool type (e.g. {@value #WEB_SEARCH_TYPE}); null for client tools
 * @param name the tool name
 * @param description description of what the tool does (client tools only)
 * @param inputSchema JSON Schema for tool parameters (client tools only)
 * @param cacheControl optional prompt-cache breakpoint anchored to this tool
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolDefinition(
    String type,
    String name,
    String description,
    @JsonProperty("input_schema") Map<String, Object> inputSchema,
    @JsonProperty("cache_control") CacheControl cacheControl) {

  /**
   * Current Anthropic web-search server-tool type. The {@code _20260318} variant includes dynamic
   * filtering (Claude filters results in code before they reach context) and is supported by every
   * model in {@link ai.singlr.anthropic.AnthropicModelId}.
   */
  public static final String WEB_SEARCH_TYPE = "web_search_20260318";

  /**
   * Current Anthropic web-fetch server-tool type; same support matrix as {@link #WEB_SEARCH_TYPE}.
   */
  public static final String WEB_FETCH_TYPE = "web_fetch_20260318";

  /**
   * Convenience constructor for a client tool without a cache breakpoint. Preserves the prior call
   * sites that predate prompt caching and server tools.
   *
   * @param name the tool name
   * @param description description of what the tool does
   * @param inputSchema JSON Schema for tool parameters
   */
  public ToolDefinition(String name, String description, Map<String, Object> inputSchema) {
    this(null, name, description, inputSchema, null);
  }

  /**
   * The web-search server tool. Executed by Anthropic during the request; results arrive as content
   * blocks with citations.
   *
   * @return a fresh server-tool definition
   */
  public static ToolDefinition webSearch() {
    return new ToolDefinition(WEB_SEARCH_TYPE, "web_search", null, null, null);
  }

  /**
   * The web-fetch server tool. Only fetches URLs already present in the conversation (user
   * messages, tool results, or prior search results) — Claude cannot fabricate targets.
   *
   * @return a fresh server-tool definition
   */
  public static ToolDefinition webFetch() {
    return new ToolDefinition(WEB_FETCH_TYPE, "web_fetch", null, null, null);
  }

  /**
   * Return a copy of this definition carrying the given prompt-cache breakpoint.
   *
   * @param cacheControl the breakpoint; non-null
   * @return a copy with {@code cache_control} set
   */
  public ToolDefinition withCacheControl(CacheControl cacheControl) {
    Objects.requireNonNull(cacheControl, "cacheControl must not be null");
    return new ToolDefinition(type, name, description, inputSchema, cacheControl);
  }
}
