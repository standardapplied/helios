/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.anthropic;

import ai.singlr.core.common.Strings;

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
  // contextWindow / maxOutputTokens mirror the documented per-model limits (Models overview, Aug
  // 2026) — operators can override per-call via ModelConfig.Builder.withMaxOutputTokens.
  // ThinkingShape per model (thinking-troubleshooting table, Aug 2026): Fable 5 and Mythos 5
  // always think; Opus 5 and Sonnet 5 run adaptive when the field is omitted; Opus 4.7/4.8 run
  // thinking-off when omitted; Opus 4.6 / Sonnet 4.6 take adaptive without xhigh (their
  // enabled+budget_tokens mode is deprecated); Haiku 4.5 supports extended thinking only.
  CLAUDE_FABLE_5("claude-fable-5", 1_000_000, 128_000, ThinkingShape.ALWAYS_ON),
  CLAUDE_MYTHOS_5("claude-mythos-5", 1_000_000, 128_000, ThinkingShape.ALWAYS_ON),
  CLAUDE_OPUS_5("claude-opus-5", 1_000_000, 128_000, ThinkingShape.ADAPTIVE_DEFAULT_ON),
  CLAUDE_SONNET_5("claude-sonnet-5", 1_000_000, 128_000, ThinkingShape.ADAPTIVE_DEFAULT_ON),
  CLAUDE_OPUS_4_8("claude-opus-4-8", 1_000_000, 128_000, ThinkingShape.ADAPTIVE),
  CLAUDE_OPUS_4_7("claude-opus-4-7", 1_000_000, 128_000, ThinkingShape.ADAPTIVE),
  CLAUDE_OPUS_4_6("claude-opus-4-6", 1_000_000, 128_000, ThinkingShape.ADAPTIVE_WITHOUT_XHIGH),
  CLAUDE_SONNET_4_6("claude-sonnet-4-6", 1_000_000, 128_000, ThinkingShape.ADAPTIVE_WITHOUT_XHIGH),
  CLAUDE_HAIKU_4_5("claude-haiku-4-5", 200_000, 64_000, ThinkingShape.LEGACY_BUDGET);

  /**
   * The thinking request shape a Claude model accepts, and what omitting the {@code thinking} field
   * means there. Drives {@code AnthropicModel}'s per-model request build; shapes whose {@link
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
     * Thinking is always on and cannot be configured: any explicit {@code thinking} config —
     * including {@code disabled} — returns a 400, so the field is always omitted and depth is
     * controlled solely via {@code output_config.effort} (Fable 5, Mythos 5). {@code
     * ThinkingLevel.NONE} still omits the field; the model thinks adaptively regardless.
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
   * Whether this model uses an adaptive-family thinking shape rather than legacy {@code
   * budget_tokens}.
   *
   * @return true for every shape except {@link ThinkingShape#LEGACY_BUDGET}
   * @deprecated use {@link #thinkingShape()}; the boolean cannot express newer models'
   *     adaptive-by-default or always-on semantics
   */
  @Deprecated(since = "2.8.0")
  public boolean usesAdaptiveThinking() {
    return thinkingShape != ThinkingShape.LEGACY_BUDGET;
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
   * wins, otherwise an ID of the form {@code <enum-id>-<suffix>} (e.g. {@code
   * claude-sonnet-4-6-20251114}) resolves to its family so legacy snapshots keep legacy request
   * semantics instead of falling into the adaptive default for unknown IDs.
   *
   * @param id the wire model identifier
   * @return the matching family, or null when no enum id is an exact or dated-prefix match
   */
  public static AnthropicModelId fromWireId(String id) {
    var exact = fromId(id);
    if (exact != null) {
      return exact;
    }
    if (Strings.isBlank(id)) {
      return null;
    }
    for (var model : values()) {
      if (id.startsWith(model.id + "-")) {
        return model;
      }
    }
    return null;
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
