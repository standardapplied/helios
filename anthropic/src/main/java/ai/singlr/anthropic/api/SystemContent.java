/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.anthropic.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * One text block inside the Claude Messages API's array-shaped {@code system} field.
 *
 * <p>The API accepts either a plain string or an array of {@code SystemContent} blocks for {@code
 * system}. Prompt caching <b>requires</b> the array form because the per-block {@link
 * #cacheControl()} annotation is the only mechanism to mark a cache breakpoint on the system
 * prefix.
 *
 * @param type the block type; always {@code "text"} for system content blocks
 * @param text the system prompt text; non-null
 * @param cacheControl optional cache breakpoint anchored to this block (and every block before it
 *     in the system array)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SystemContent(
    String type, String text, @JsonProperty("cache_control") CacheControl cacheControl) {

  /** Block type discriminator used by the Anthropic API for text system blocks. */
  public static final String TYPE_TEXT = "text";

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if {@code type} or {@code text} is null
   */
  public SystemContent {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(text, "text must not be null");
  }

  /**
   * Build a plain text system block without a cache breakpoint.
   *
   * @param text the system prompt text; non-null
   * @return a fresh {@link SystemContent}
   */
  public static SystemContent text(String text) {
    return new SystemContent(TYPE_TEXT, text, null);
  }

  /**
   * Return a copy of this block carrying the given cache breakpoint. Use to mark the last block of
   * a system array so the entire system prefix becomes cacheable.
   *
   * @param cacheControl the breakpoint; non-null
   * @return a copy with {@code cache_control} set
   */
  public SystemContent withCacheControl(CacheControl cacheControl) {
    Objects.requireNonNull(cacheControl, "cacheControl must not be null");
    return new SystemContent(type, text, cacheControl);
  }
}
