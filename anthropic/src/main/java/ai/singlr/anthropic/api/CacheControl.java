/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.anthropic.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/**
 * Cache-control annotation on a Claude request content block, tool definition, or system block.
 *
 * <p>The Anthropic API caches the prompt prefix up to and including every annotated block. Up to
 * four breakpoints may appear per request; additional breakpoints are silently ignored by the
 * server. Cache lifetime is {@code 5m} by default; pass {@code "1h"} via {@link #ephemeral(String)}
 * for the long-lived cache class. The provider only bills cache writes once and bills subsequent
 * cache reads at the discounted rate documented at <a
 * href="https://platform.claude.com/docs/en/build-with-claude/prompt-caching">Anthropic's
 * prompt-caching docs</a>.
 *
 * @param type the cache class; only {@code "ephemeral"} is currently defined by the API
 * @param ttl optional cache lifetime hint — {@code null} (default 5 minutes) or {@code "1h"}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CacheControl(String type, String ttl) {

  /** Cache class understood by every cache-aware Claude endpoint. */
  public static final String TYPE_EPHEMERAL = "ephemeral";

  /** Five-minute default — the implicit TTL when {@link #ttl()} is {@code null}. */
  public static final String TTL_5_MINUTES = "5m";

  /** One-hour extended TTL — opt-in via {@link #ephemeral(String)}. */
  public static final String TTL_1_HOUR = "1h";

  /**
   * Canonical constructor.
   *
   * @throws IllegalArgumentException if {@code type} is null or blank
   */
  public CacheControl {
    Objects.requireNonNull(type, "type must not be null");
    if (type.isBlank()) {
      throw new IllegalArgumentException("type must not be blank");
    }
  }

  /**
   * Default 5-minute ephemeral cache breakpoint.
   *
   * @return an ephemeral cache-control with no TTL hint
   */
  public static CacheControl ephemeral() {
    return new CacheControl(TYPE_EPHEMERAL, null);
  }

  /**
   * Ephemeral cache breakpoint with an explicit TTL hint.
   *
   * @param ttl Anthropic-defined TTL string; {@link #TTL_5_MINUTES} or {@link #TTL_1_HOUR}
   * @return an ephemeral cache-control carrying {@code ttl}
   */
  public static CacheControl ephemeral(String ttl) {
    return new CacheControl(TYPE_EPHEMERAL, ttl);
  }
}
