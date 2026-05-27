/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.hooks;

import ai.singlr.core.common.Strings;
import ai.singlr.core.model.Message;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Outcome returned by a {@link Hook} for the non-observe phases (every phase except {@link
 * OnStreamEventHook}). Eight subtypes carry the interpretations the agent loop applies.
 *
 * <p>Semantics depend on phase — see the table in spec §9.3 for the full matrix. Short version:
 *
 * <ul>
 *   <li>{@link Continue} — let the loop proceed.
 *   <li>{@link MutateArgs} — replace tool arguments ({@link PreToolUseHook}).
 *   <li>{@link MutateHistory} — replace conversation history ({@link PreModelTurnHook}, {@link
 *       PreCompactHook}).
 *   <li>{@link MutateText} — replace user-message text ({@link OnUserMessageHook}).
 *   <li>{@link MutateResult} — replace tool-result output ({@link PostToolUseHook}).
 *   <li>{@link Block} — refuse the action, emit a blocked event ({@link PreToolUseHook}, {@link
 *       OnUserMessageHook}).
 *   <li>{@link Inject} — inject a synthetic user message before the next turn.
 *   <li>{@link Stop} — terminate the session with the given result string.
 * </ul>
 *
 * <p>Returning a mutation variant at a phase where it is not meaningful is harmless — the loop
 * treats it as {@link Continue}. The typed variants exist so hook authors cannot misspell a key or
 * pass the wrong payload type; the compiler catches the mistake.
 */
public sealed interface HookOutcome
    permits HookOutcome.Continue,
        HookOutcome.MutateArgs,
        HookOutcome.MutateHistory,
        HookOutcome.MutateText,
        HookOutcome.MutateResult,
        HookOutcome.Block,
        HookOutcome.Inject,
        HookOutcome.Stop {

  /**
   * Singleton {@link Continue} outcome. Cheaper than allocating a fresh record per hook fire.
   *
   * @return the shared continue value
   */
  static HookOutcome cont() {
    return Continue.INSTANCE;
  }

  /**
   * Replace tool arguments at {@link PreToolUseHook}.
   *
   * @param args the replacement arguments; non-null
   * @return a fresh {@link MutateArgs}
   * @throws NullPointerException if {@code args} is null
   */
  static HookOutcome mutateArgs(Map<String, Object> args) {
    return new MutateArgs(args);
  }

  /**
   * Replace conversation history at {@link PreModelTurnHook} or {@link PreCompactHook}.
   *
   * @param history the replacement history; non-null (may be empty)
   * @return a fresh {@link MutateHistory}
   * @throws NullPointerException if {@code history} is null
   */
  static HookOutcome mutateHistory(List<Message> history) {
    return new MutateHistory(history);
  }

  /**
   * Replace user-message text at {@link OnUserMessageHook}.
   *
   * @param text the replacement text; non-blank
   * @return a fresh {@link MutateText}
   * @throws NullPointerException if {@code text} is null
   * @throws IllegalArgumentException if {@code text} is blank
   */
  static HookOutcome mutateText(String text) {
    return new MutateText(text);
  }

  /**
   * Replace tool-result output at {@link PostToolUseHook}.
   *
   * @param output the replacement output; non-null (may be empty)
   * @return a fresh {@link MutateResult}
   * @throws NullPointerException if {@code output} is null
   */
  static HookOutcome mutateResult(String output) {
    return new MutateResult(output);
  }

  /**
   * Block outcome that records a human-readable reason.
   *
   * @param reason non-blank explanation
   * @return a fresh {@link Block}
   * @throws NullPointerException if {@code reason} is null
   * @throws IllegalArgumentException if {@code reason} is blank
   */
  static HookOutcome block(String reason) {
    return new Block(reason);
  }

  /**
   * Inject outcome that queues the given text as a synthetic user message.
   *
   * @param userMessage non-blank text
   * @return a fresh {@link Inject}
   * @throws NullPointerException if {@code userMessage} is null
   * @throws IllegalArgumentException if {@code userMessage} is blank
   */
  static HookOutcome inject(String userMessage) {
    return new Inject(userMessage);
  }

  /**
   * Stop outcome that terminates the session with the given result.
   *
   * @param result non-blank terminal result string
   * @return a fresh {@link Stop}
   * @throws NullPointerException if {@code result} is null
   * @throws IllegalArgumentException if {@code result} is blank
   */
  static HookOutcome stop(String result) {
    return new Stop(result);
  }

  /** Proceed with the action the hook was inspecting. */
  record Continue() implements HookOutcome {

    static final Continue INSTANCE = new Continue();
  }

  /**
   * Replace tool arguments at {@link PreToolUseHook}.
   *
   * @param args the replacement arguments; non-null, defensively copied
   */
  record MutateArgs(Map<String, Object> args) implements HookOutcome {

    public MutateArgs {
      Objects.requireNonNull(args, "args must not be null");
      args = Map.copyOf(args);
    }
  }

  /**
   * Replace conversation history at {@link PreModelTurnHook} or {@link PreCompactHook}.
   *
   * @param history the replacement history; non-null, defensively copied (may be empty)
   */
  record MutateHistory(List<Message> history) implements HookOutcome {

    public MutateHistory {
      Objects.requireNonNull(history, "history must not be null");
      history = List.copyOf(history);
    }
  }

  /**
   * Replace user-message text at {@link OnUserMessageHook}.
   *
   * @param text the replacement text; non-blank
   */
  record MutateText(String text) implements HookOutcome {

    public MutateText {
      Objects.requireNonNull(text, "text must not be null");
      if (Strings.isBlank(text)) {
        throw new IllegalArgumentException("text must not be blank");
      }
    }
  }

  /**
   * Replace tool-result output at {@link PostToolUseHook}.
   *
   * @param output the replacement output; non-null (may be empty — empty tool results are valid)
   */
  record MutateResult(String output) implements HookOutcome {

    public MutateResult {
      Objects.requireNonNull(output, "output must not be null");
    }
  }

  /**
   * Refuse the action and surface a human-readable reason.
   *
   * @param reason non-blank reason text
   */
  record Block(String reason) implements HookOutcome {

    public Block {
      Objects.requireNonNull(reason, "reason must not be null");
      if (Strings.isBlank(reason)) {
        throw new IllegalArgumentException("reason must not be blank");
      }
    }
  }

  /**
   * Inject a synthetic user message before the next turn.
   *
   * @param userMessage non-blank message text
   */
  record Inject(String userMessage) implements HookOutcome {

    public Inject {
      Objects.requireNonNull(userMessage, "userMessage must not be null");
      if (Strings.isBlank(userMessage)) {
        throw new IllegalArgumentException("userMessage must not be blank");
      }
    }
  }

  /**
   * Terminate the session with a custom result.
   *
   * @param result non-blank terminal result text
   */
  record Stop(String result) implements HookOutcome {

    public Stop {
      Objects.requireNonNull(result, "result must not be null");
      if (Strings.isBlank(result)) {
        throw new IllegalArgumentException("result must not be blank");
      }
    }
  }
}
