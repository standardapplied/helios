/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.model.Reasoning.Display;
import com.standardapplied.helios.core.model.Reasoning.Level;
import com.standardapplied.helios.core.provider.ReasoningSupport;
import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Curated Anthropic Claude model identifiers carrying known-good metadata.
 *
 * <p>Each enum constant maps to a specific Claude model available through the Messages API and
 * records the metadata the request builder needs (context window, output ceiling, reasoning rules).
 * Membership here is <em>not</em> a gate: {@link AnthropicProvider} also accepts any {@code claude}
 * model ID it does not recognize, so deployers can adopt a new Claude release before this enum
 * catches up. An unrecognized ID falls back to {@link ReasoningRules#UNCATALOGUED} and {@code
 * 32_000} output tokens — see {@link #hasClaudePrefix(String)}.
 */
public enum AnthropicModelId {
  // contextWindow / maxOutputTokens mirror the documented per-model limits (Models overview, Oct
  // 2026) — operators can override per-call via ModelConfig.Builder.withMaxOutputTokens.
  // ReasoningRules per model (Thinking "Configuring thinking" table and the Fable 5.1 / Opus 5.5 /
  // Sonnet 5.5 migration notes, Oct 2026): Fable 5/5.1, Mythos 5/5.1 and Opus 5.5 always think;
  // display "updates" exists on Fable 5/5.1, Mythos 5.1, Opus 5.5 and Sonnet 5.5; Sonnet 5.5 turns
  // up-front thinking off with between_tools; Opus 5 and Sonnet 5 think unless sent disabled; Opus
  // 4.6 / Sonnet 4.6 have no xhigh and accept sampling parameters; Haiku 4.5 takes no effort.
  CLAUDE_FABLE_5_1("claude-fable-5-1", 1_000_000, 128_000, ReasoningRules.ALWAYS_ON_WITH_PROGRESS),
  CLAUDE_MYTHOS_5_1(
      "claude-mythos-5-1", 1_000_000, 128_000, ReasoningRules.ALWAYS_ON_WITH_PROGRESS),
  CLAUDE_FABLE_5("claude-fable-5", 1_000_000, 128_000, ReasoningRules.ALWAYS_ON_WITH_PROGRESS),
  CLAUDE_MYTHOS_5("claude-mythos-5", 1_000_000, 128_000, ReasoningRules.ALWAYS_ON),
  CLAUDE_OPUS_5_5("claude-opus-5-5", 1_000_000, 128_000, ReasoningRules.ALWAYS_ON_WITH_PROGRESS),
  CLAUDE_OPUS_5("claude-opus-5", 1_000_000, 128_000, ReasoningRules.OFF_WHEN_DISABLED),
  CLAUDE_SONNET_5_5("claude-sonnet-5-5", 1_000_000, 128_000, ReasoningRules.OFF_BETWEEN_TOOLS),
  CLAUDE_SONNET_5("claude-sonnet-5", 1_000_000, 128_000, ReasoningRules.OFF_WHEN_DISABLED),
  CLAUDE_OPUS_4_8("claude-opus-4-8", 1_000_000, 128_000, ReasoningRules.OFF_WHEN_OMITTED),
  CLAUDE_OPUS_4_7("claude-opus-4-7", 1_000_000, 128_000, ReasoningRules.OFF_WHEN_OMITTED),
  CLAUDE_OPUS_4_6("claude-opus-4-6", 1_000_000, 128_000, ReasoningRules.WITHOUT_XHIGH),
  CLAUDE_SONNET_4_6("claude-sonnet-4-6", 1_000_000, 128_000, ReasoningRules.WITHOUT_XHIGH),
  CLAUDE_HAIKU_4_5("claude-haiku-4-5", 200_000, 64_000, ReasoningRules.WITHOUT_EFFORT);

  /** How a model is told to stop reasoning. */
  public enum Off {
    /** It cannot stop: {@code Reasoning.Off} is rejected. */
    REJECTED,

    /** The {@code thinking} field is left out; the model does not think without it. */
    OMITTED,

    /** {@code thinking.type=disabled}. */
    DISABLED,

    /**
     * {@code thinking.type=between_tools}, with no other thinking field: no up-front thinking,
     * while the notes written between tool calls still return.
     */
    BETWEEN_TOOLS
  }

  /** When a model accepts {@code temperature} and {@code top_p}. */
  public enum Sampling {
    /** Never: either one is rejected whenever it is set, with or without reasoning. */
    REJECTED,

    /** Either one, never both together. */
    ONE_OF,

    /**
     * Either one, never both together; alongside {@code Reasoning.Effort} {@code temperature} is
     * rejected and {@code top_p} must lie between 0.95 and 1 inclusive.
     */
    ONE_OF_WITHOUT_EFFORT,

    /** Whatever is set is sent: the API judges a model the catalogue does not know. */
    UNCHECKED
  }

  /**
   * What a model accepts for reasoning and sampling, and how it is told to stop reasoning. An
   * accepted {@code Reasoning.Effort} is sent as {@code thinking.type=adaptive} with its display
   * and an {@code output_config.effort}.
   */
  public enum ReasoningRules {
    /** Always thinks, with progress updates (Fable 5 / 5.1, Mythos 5.1, Opus 5.5). */
    ALWAYS_ON_WITH_PROGRESS(Off.REJECTED, Level.LOW, Display.PROGRESS, Sampling.REJECTED),

    /** Always thinks (Mythos 5). */
    ALWAYS_ON(Off.REJECTED, Level.LOW, Display.SUMMARY, Sampling.REJECTED),

    /** Stops up-front thinking with {@code between_tools}, has progress updates (Sonnet 5.5). */
    OFF_BETWEEN_TOOLS(Off.BETWEEN_TOOLS, Level.LOW, Display.PROGRESS, Sampling.REJECTED),

    /** Thinks unless sent {@code disabled} (Opus 5, Sonnet 5). */
    OFF_WHEN_DISABLED(Off.DISABLED, Level.LOW, Display.SUMMARY, Sampling.REJECTED),

    /** Thinks only when asked to (Opus 4.7, 4.8). */
    OFF_WHEN_OMITTED(Off.OMITTED, Level.LOW, Display.SUMMARY, Sampling.REJECTED),

    /** Thinks only when asked to, has no {@code xhigh} (Opus 4.6, Sonnet 4.6). */
    WITHOUT_XHIGH(
        Off.OMITTED,
        EnumSet.of(Level.LOW, Level.MEDIUM, Level.HIGH, Level.MAX),
        Display.SUMMARY,
        Sampling.ONE_OF_WITHOUT_EFFORT),

    /** Takes no effort (Haiku 4.5). */
    WITHOUT_EFFORT(Off.OMITTED, EnumSet.noneOf(Level.class), Display.SUMMARY, Sampling.ONE_OF),

    /**
     * A model the catalogue does not know: accepts every value, sends the current-generation shape
     * and spells off as {@code disabled}; the API judges what the model accepts.
     */
    UNCATALOGUED(Off.DISABLED, ReasoningSupport.ANY.levels(), Display.PROGRESS, Sampling.UNCHECKED);

    private final Off off;
    private final ReasoningSupport support;
    private final Sampling sampling;

    ReasoningRules(Off off, Level lowest, Display widest, Sampling sampling) {
      this(off, EnumSet.range(lowest, Level.MAX), widest, sampling);
    }

    ReasoningRules(Off off, Set<Level> levels, Display widest, Sampling sampling) {
      this.off = off;
      this.support =
          new ReasoningSupport(
              off != Off.REJECTED,
              levels,
              levels.isEmpty() ? Set.of() : EnumSet.range(Display.HIDDEN, widest));
      this.sampling = sampling;
    }

    /**
     * How the model is told to stop reasoning.
     *
     * @return the off spelling; {@link Off#REJECTED} when it cannot stop
     */
    public Off off() {
      return off;
    }

    /**
     * The reasoning values the model accepts.
     *
     * @return the support
     */
    public ReasoningSupport support() {
      return support;
    }

    /**
     * When the model accepts {@code temperature} and {@code top_p}.
     *
     * @return the sampling rule
     */
    public Sampling sampling() {
      return sampling;
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
  private final ReasoningRules reasoning;

  AnthropicModelId(String id, int contextWindow, int maxOutputTokens, ReasoningRules reasoning) {
    this.id = id;
    this.contextWindow = contextWindow;
    this.maxOutputTokens = maxOutputTokens;
    this.reasoning = reasoning;
  }

  /**
   * What this model accepts for reasoning and sampling. Read only by the request builder, the one
   * place that turns it into request fields.
   *
   * @return the rules; non-null
   */
  public ReasoningRules reasoning() {
    return reasoning;
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
   * semantics instead of taking the uncatalogued rules.
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
