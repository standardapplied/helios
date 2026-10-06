/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.core.common.Strings;
import java.util.regex.Pattern;

/**
 * Curated Anthropic Claude model identifiers carrying known-good metadata.
 *
 * <p>Each enum constant maps to a specific Claude model available through the Messages API and
 * records the metadata the request builder needs (context window, output ceiling, thinking shape).
 * Membership here is <em>not</em> a gate: {@link AnthropicProvider} also accepts any {@code claude}
 * model ID it does not recognize, so deployers can adopt a new Claude release before this enum
 * catches up. An unrecognized ID falls back to {@link AnthropicProvider}'s defaults (adaptive
 * thinking, {@code 32_000} output tokens) — see {@link #hasClaudePrefix(String)}.
 */
public enum AnthropicModelId {
  // contextWindow / maxOutputTokens mirror the documented per-model limits (Models overview, Oct
  // 2026) — operators can override per-call via ModelConfig.Builder.withMaxOutputTokens.
  // ThinkingShape per model (Thinking "Configuring thinking" table, Oct 2026): Fable 5/5.1, Mythos
  // 5/5.1 and Opus 5.5 always think; Sonnet 5.5 always thinks but can drop up-front thinking with
  // between_tools; Opus 5 and Sonnet 5 run adaptive when the field is omitted; Opus 4.7/4.8 run
  // thinking-off when omitted; Opus 4.6 / Sonnet 4.6 take adaptive without xhigh (their
  // enabled+budget_tokens mode is deprecated); Haiku 4.5 supports extended thinking only.
  CLAUDE_FABLE_5_1("claude-fable-5-1", 1_000_000, 128_000, ThinkingShape.ALWAYS_ON),
  CLAUDE_MYTHOS_5_1("claude-mythos-5-1", 1_000_000, 128_000, ThinkingShape.ALWAYS_ON),
  CLAUDE_FABLE_5("claude-fable-5", 1_000_000, 128_000, ThinkingShape.ALWAYS_ON),
  CLAUDE_MYTHOS_5("claude-mythos-5", 1_000_000, 128_000, ThinkingShape.ALWAYS_ON),
  CLAUDE_OPUS_5_5("claude-opus-5-5", 1_000_000, 128_000, ThinkingShape.ALWAYS_ON),
  CLAUDE_OPUS_5("claude-opus-5", 1_000_000, 128_000, ThinkingShape.ADAPTIVE_DEFAULT_ON),
  CLAUDE_SONNET_5_5("claude-sonnet-5-5", 1_000_000, 128_000, ThinkingShape.ADAPTIVE_BETWEEN_TOOLS),
  CLAUDE_SONNET_5("claude-sonnet-5", 1_000_000, 128_000, ThinkingShape.ADAPTIVE_DEFAULT_ON),
  CLAUDE_OPUS_4_8("claude-opus-4-8", 1_000_000, 128_000, ThinkingShape.ADAPTIVE),
  CLAUDE_OPUS_4_7("claude-opus-4-7", 1_000_000, 128_000, ThinkingShape.ADAPTIVE),
  CLAUDE_OPUS_4_6("claude-opus-4-6", 1_000_000, 128_000, ThinkingShape.ADAPTIVE_WITHOUT_XHIGH),
  CLAUDE_SONNET_4_6("claude-sonnet-4-6", 1_000_000, 128_000, ThinkingShape.ADAPTIVE_WITHOUT_XHIGH),
  CLAUDE_HAIKU_4_5("claude-haiku-4-5", 200_000, 64_000, ThinkingShape.LEGACY_BUDGET);

  /**
   * The thinking request shape a Claude model accepts, and what omitting the {@code thinking} field
   * means there. Drives the per-model request build; shapes whose {@link
   * #acceptsSamplingParameters()} is false reject {@code temperature}/{@code top_p} with a 400 on
   * every request, so the request builder never sends them.
   */
  public enum ThinkingShape {
    /**
     * {@code thinking.type=enabled} + {@code budget_tokens}, the only mode on extended-thinking
     * models (Haiku 4.5); {@code adaptive} is rejected. Omitting the field runs without thinking.
     * Sampling parameters allowed when thinking is off; {@code xhigh}/{@code max} have no
     * equivalent and fail fast.
     */
    LEGACY_BUDGET,

    /**
     * {@code thinking.type=adaptive} + sibling {@code output_config.effort} with {@code low},
     * {@code medium}, {@code high} and {@code max} but not {@code xhigh}; omitting the field runs
     * without thinking (Opus 4.6, Sonnet 4.6). Sampling parameters allowed when thinking is off.
     * Their {@code enabled}+{@code budget_tokens} mode is deprecated and no longer sent.
     */
    ADAPTIVE_WITHOUT_XHIGH,

    /**
     * {@code thinking.type=adaptive} + sibling {@code output_config.effort} across the full {@code
     * low}..{@code max} range; omitting the field runs without thinking (Opus 4.7, Opus 4.8).
     */
    ADAPTIVE,

    /**
     * Adaptive shape, but omitting the field runs <em>with</em> adaptive thinking — turning
     * thinking off requires an explicit {@code thinking.type=disabled} (Opus 5, Sonnet 5).
     */
    ADAPTIVE_DEFAULT_ON,

    /**
     * Adaptive shape whose up-front thinking is turned off with {@code thinking.type=between_tools}
     * instead of {@code disabled}, which returns a 400 (Sonnet 5.5). {@code between_tools} takes no
     * sibling field, is accepted at effort {@code high} or below, and still returns the model's
     * progress notes between tool calls as {@code thinking} blocks with text. {@code
     * ThinkingLevel.NONE} sends it bare, so the API's default effort ({@code high}) applies.
     */
    ADAPTIVE_BETWEEN_TOOLS,

    /**
     * Thinking is always on: {@code disabled} and {@code enabled}+{@code budget_tokens} return a
     * 400 at every effort, and depth is controlled solely via {@code output_config.effort} (Fable
     * 5/5.1, Mythos 5/5.1, Opus 5.5). {@code ThinkingLevel.NONE} omits both fields, so the model
     * thinks at the API's default effort ({@code medium} on Opus 5.5, {@code high} on the others)
     * and returns no thinking text; every other level sends {@code thinking.type=adaptive} with a
     * summarized display plus the effort.
     */
    ALWAYS_ON;

    /**
     * Whether the model accepts {@code temperature} / {@code top_p}. Claude 4.7 and later reject
     * them with a 400 on every request regardless of thinking configuration; 4.6 and earlier accept
     * them while thinking is off.
     *
     * @return true for {@link #LEGACY_BUDGET} and {@link #ADAPTIVE_WITHOUT_XHIGH}
     */
    public boolean acceptsSamplingParameters() {
      return this == LEGACY_BUDGET || this == ADAPTIVE_WITHOUT_XHIGH;
    }
  }

  /**
   * Prefix shared by every Anthropic Claude model ID. An ID starting with this prefix is treated as
   * a Claude model even when it is not (yet) an enum constant, so a newly-released Claude can be
   * used against the default endpoint without waiting for a framework release.
   */
  public static final String CLAUDE_ID_PREFIX = "claude";

  private static final Pattern DATED_SNAPSHOT = Pattern.compile("(.+)-\\d{8}");

  private final String id;
  private final int contextWindow;
  private final int maxOutputTokens;
  private final ThinkingShape thinkingShape;

  AnthropicModelId(String id, int contextWindow, int maxOutputTokens, ThinkingShape thinkingShape) {
    this.id = id;
    this.contextWindow = contextWindow;
    this.maxOutputTokens = maxOutputTokens;
    this.thinkingShape = thinkingShape;
  }

  /**
   * The thinking request shape this model accepts. See {@link ThinkingShape} for the per-shape
   * request semantics.
   *
   * @return the shape; non-null
   */
  public ThinkingShape thinkingShape() {
    return thinkingShape;
  }

  /**
   * Whether this model accepts forced tool use ({@code tool_choice.type} {@code any} or {@code
   * tool}). Fable 5.1, Mythos 5.1, Opus 5.5 and Sonnet 5.5 reject both with a 400 on every request;
   * {@code auto} and {@code none} are unaffected. {@link AnthropicProvider} fails fast when
   * creating such a model rather than letting the request 400.
   *
   * @return false for {@link #CLAUDE_FABLE_5_1}, {@link #CLAUDE_MYTHOS_5_1}, {@link
   *     #CLAUDE_OPUS_5_5} and {@link #CLAUDE_SONNET_5_5}, true otherwise
   */
  public boolean acceptsForcedToolChoice() {
    return switch (this) {
      case CLAUDE_FABLE_5_1, CLAUDE_MYTHOS_5_1, CLAUDE_OPUS_5_5, CLAUDE_SONNET_5_5 -> false;
      default -> true;
    };
  }

  /**
   * Returns the API model identifier string.
   *
   * @return the model ID used in API requests
   */
  public String id() {
    return id;
  }

  /**
   * Returns the context window size in tokens.
   *
   * @return the context window size
   */
  public int contextWindow() {
    return contextWindow;
  }

  /**
   * Returns the maximum output tokens this model can generate in a single response. Used as the
   * fallback when {@code ModelConfig.maxOutputTokens()} is unset, so callers don't silently get
   * truncated at a hardcoded framework default.
   *
   * @return the per-model output ceiling
   */
  public int maxOutputTokens() {
    return maxOutputTokens;
  }

  /**
   * Finds an AnthropicModelId by its string identifier.
   *
   * @param id the model identifier string
   * @return the matching AnthropicModelId, or null if not found
   */
  public static AnthropicModelId fromId(String id) {
    if (Strings.isBlank(id)) {
      return null;
    }
    for (var model : values()) {
      if (model.id.equals(id)) {
        return model;
      }
    }
    return null;
  }

  /**
   * Resolves a wire model ID to curated metadata, accepting dated snapshot variants: an exact match
   * wins, otherwise an ID of the form {@code <enum-id>-<yyyymmdd>} (e.g. {@code
   * claude-haiku-4-5-20251001}) resolves to its family so legacy snapshots keep legacy request
   * semantics instead of falling into the adaptive default for unknown IDs.
   *
   * <p>Only an eight-digit snapshot date counts as a suffix. A newer release whose ID merely
   * extends an older one ({@code claude-opus-5-5} extends {@code claude-opus-5}) is a different
   * model with its own request rules and must not inherit its predecessor's.
   *
   * @param id the wire model identifier
   * @return the matching family, or null when no enum id is an exact or dated-snapshot match
   */
  public static AnthropicModelId fromWireId(String id) {
    var exact = fromId(id);
    if (exact != null || Strings.isBlank(id)) {
      return exact;
    }
    var matcher = DATED_SNAPSHOT.matcher(id);
    return matcher.matches() ? fromId(matcher.group(1)) : null;
  }

  /**
   * Checks whether the given model ID is a known enum constant carrying curated metadata.
   *
   * <p>This is strict enum membership, distinct from {@link #hasClaudePrefix(String)}: a freshly
   * released {@code claude} model is <em>usable</em> (prefix match) but not yet {@code supported}
   * here (no metadata) until added to the enum.
   *
   * @param id the model identifier string
   * @return true if the model is a known enum constant
   */
  public static boolean isSupported(String id) {
    return fromId(id) != null;
  }

  /**
   * Checks whether the given model ID belongs to the Claude family by its {@value
   * #CLAUDE_ID_PREFIX} prefix, independent of whether it is a known enum constant. This is the gate
   * {@link AnthropicProvider} uses to accept unrecognized-but-Claude IDs against the default
   * endpoint.
   *
   * @param id the model identifier string
   * @return true if {@code id} is non-blank and starts with {@value #CLAUDE_ID_PREFIX}
   */
  public static boolean hasClaudePrefix(String id) {
    return !Strings.isBlank(id) && id.startsWith(CLAUDE_ID_PREFIX);
  }
}
