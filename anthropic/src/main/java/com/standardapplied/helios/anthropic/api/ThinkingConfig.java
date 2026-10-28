/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.anthropic.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Thinking configuration for the Claude Messages API. Which shape a model accepts is declared by
 * {@link com.standardapplied.helios.anthropic.AnthropicModelId#reasoning()}:
 *
 * <ul>
 *   <li><b>Adaptive</b>: {@code {"type":"adaptive","display":...}}, with thinking strength set by
 *       the sibling {@code output_config.effort} field. Built via {@link #adaptive(String)}.
 *   <li><b>Between tools</b> (Sonnet 5.5): {@code {"type":"between_tools"}}, no up-front thinking
 *       while the notes written between tool calls still return; any sibling field is a 400. Built
 *       via {@link #betweenTools()}.
 *   <li><b>Disabled</b>: {@code {"type":"disabled"}}. Built via {@link #disabled()}.
 * </ul>
 *
 * <p>On the adaptive shape a model that binds its thinking blocks to their prefix also takes {@link
 * BlockBinding}, under the beta {@code thinking-binding-controls-2026-08-01}.
 *
 * @param type {@code "adaptive"}, {@code "between_tools"} or {@code "disabled"}
 * @param display {@code "omitted"}, {@code "summarized"} or {@code "updates"} on the adaptive
 *     shape; null omits the field from the wire
 * @param blockBinding what the API does with a replayed thinking block whose prefix changed; null
 *     omits the field
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ThinkingConfig(
    String type, String display, @JsonProperty("block_binding") BlockBinding blockBinding) {

  /**
   * How the API treats a thinking block replayed after its prefix changed.
   *
   * @param prefixMismatchBehavior {@code "error"} rejects the request with a 400 naming the block
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record BlockBinding(
      @JsonProperty("prefix_mismatch_behavior") String prefixMismatchBehavior) {

    /** A mismatched block rejects the request, on every account whatever its age. */
    public static BlockBinding rejectMismatch() {
      return new BlockBinding("error");
    }
  }

  /**
   * Adaptive thinking returned as {@code display}: {@code "omitted"} returns empty thinking blocks,
   * {@code "summarized"} a summary of the reasoning, {@code "updates"} only the progress notes
   * written between tool calls (beta {@code thinking-display-updates-2026-08-18}).
   */
  public static ThinkingConfig adaptive(String display) {
    return new ThinkingConfig("adaptive", display, null);
  }

  /** This configuration with {@code blockBinding}. */
  public ThinkingConfig withBlockBinding(BlockBinding blockBinding) {
    return new ThinkingConfig(type, display, blockBinding);
  }

  /** Thinking off, on models that think unless told otherwise. */
  public static ThinkingConfig disabled() {
    return new ThinkingConfig("disabled", null, null);
  }

  /** Sonnet 5.5's thinking off: no up-front thinking, with no other thinking field. */
  public static ThinkingConfig betweenTools() {
    return new ThinkingConfig("between_tools", null, null);
  }
}
