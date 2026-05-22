/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.anthropic;

import ai.singlr.anthropic.api.CacheControl;

/**
 * Anthropic prompt-caching policy for an {@link AnthropicModel} instance.
 *
 * <p>Sealed: three concrete shapes covering every supported TTL plus an opt-out. New TTLs that
 * Anthropic introduces require adding a new permitted subtype, which is a deliberate breaking
 * change for {@code switch} consumers — the compiler flags missing branches.
 *
 * <h2>Pricing ratios (per Anthropic docs)</h2>
 *
 * <table border="1">
 *   <caption>Anthropic prompt-cache pricing multipliers against the base input rate</caption>
 *   <tr><th>Policy</th><th>Write multiplier</th><th>Read multiplier</th><th>TTL</th></tr>
 *   <tr><td>{@link #shortLived() short-lived}</td><td>1.25×</td><td>0.10×</td><td>5 minutes</td></tr>
 *   <tr><td>{@link #longLived() long-lived}</td><td>2.00×</td><td>0.10×</td><td>1 hour</td></tr>
 *   <tr><td>{@link #disabled() disabled}</td><td>n/a</td><td>n/a</td><td>n/a</td></tr>
 * </table>
 *
 * <p>The short-lived TTL is the default for {@link AnthropicModel}. The long-lived TTL costs more
 * to write (2× vs 1.25×) but reads at the same 0.10× discount — pick it when the cache prefix is
 * expected to be reused for more than 5 minutes between turns (e.g. an SME reviewing a long report,
 * an agent that wakes up periodically against a stable system prompt). Disabled is the right choice
 * for one-shot calls where the cache write premium never pays back.
 *
 * <h2>Ordering constraint when mixing TTLs in the same request</h2>
 *
 * Anthropic requires 1-hour breakpoints to appear <b>before</b> 5-minute breakpoints in prompt
 * order. Helios's current implementation uses a single policy for all breakpoints in one request so
 * this constraint is satisfied trivially — there is no API to mix TTLs within a single request
 * today. Should a future caller need that, build a dedicated wrapper that orders the breakpoints
 * correctly per Anthropic's spec.
 */
public sealed interface CachePolicy
    permits CachePolicy.Disabled, CachePolicy.ShortLived, CachePolicy.LongLived {

  /**
   * Prompt caching disabled. {@link AnthropicModel#buildRequest} emits no {@code cache_control}
   * annotations; the server returns billing with {@code cache_creation_input_tokens=0} and {@code
   * cache_read_input_tokens=0}. Use for one-shot calls or compliance environments that prohibit
   * server-side caching.
   *
   * @return the disabled-caching singleton
   */
  static CachePolicy disabled() {
    return Disabled.INSTANCE;
  }

  /**
   * 5-minute ephemeral cache — Anthropic's default. Cache writes bill at 1.25× the base input rate;
   * reads at 0.10×. Right choice for the typical agent loop where successive turns happen within
   * seconds of each other.
   *
   * @return the short-lived-caching singleton
   */
  static CachePolicy shortLived() {
    return ShortLived.INSTANCE;
  }

  /**
   * 1-hour ephemeral cache. Cache writes bill at 2.00× the base input rate; reads at 0.10×. The
   * extra write premium pays back when the prefix is reused for more than 5 minutes — for example a
   * human-in-the-loop session where the agent waits on a reviewer, or a scheduled agent that wakes
   * every quarter-hour against a stable system prompt.
   *
   * @return the long-lived-caching singleton
   */
  static CachePolicy longLived() {
    return LongLived.INSTANCE;
  }

  /**
   * Whether this policy emits {@code cache_control} breakpoints on the outgoing request.
   *
   * @return {@code true} for {@link ShortLived} and {@link LongLived}; {@code false} for {@link
   *     Disabled}
   */
  boolean isEnabled();

  /**
   * The {@link CacheControl} breakpoint to attach to each cacheable slot (system, last tool, last
   * message) when this policy is enabled. Returns {@code null} when {@link #isEnabled()} is {@code
   * false} so callers can skip annotation entirely.
   *
   * @return the breakpoint, or {@code null} when caching is disabled
   */
  CacheControl breakpoint();

  /** Disabled-caching singleton; emits no {@code cache_control} on the request. */
  record Disabled() implements CachePolicy {

    static final Disabled INSTANCE = new Disabled();

    @Override
    public boolean isEnabled() {
      return false;
    }

    @Override
    public CacheControl breakpoint() {
      return null;
    }
  }

  /** Short-lived (5m) caching singleton. */
  record ShortLived() implements CachePolicy {

    static final ShortLived INSTANCE = new ShortLived();

    @Override
    public boolean isEnabled() {
      return true;
    }

    @Override
    public CacheControl breakpoint() {
      return CacheControl.ephemeral();
    }
  }

  /** Long-lived (1h) caching singleton. */
  record LongLived() implements CachePolicy {

    static final LongLived INSTANCE = new LongLived();

    @Override
    public boolean isEnabled() {
      return true;
    }

    @Override
    public CacheControl breakpoint() {
      return CacheControl.ephemeral(CacheControl.TTL_1_HOUR);
    }
  }
}
