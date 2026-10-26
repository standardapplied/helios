/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.hooks;

import com.standardapplied.helios.core.common.JsonSnapshot;
import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.model.Message;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Outcome returned by a {@link Hook} for every phase except the observe-only {@link
 * OnStreamEventHook}. Each phase method returns its phase's sealed sub-interface, which admits
 * exactly the outcomes that phase honours, so an outcome the phase would ignore does not compile.
 * The six sub-interfaces below are the whole matrix; each record implements the phases that honour
 * it:
 *
 * <ul>
 *   <li>{@link Continue} — let the loop proceed.
 *   <li>{@link MutateArgs} — replace tool arguments.
 *   <li>{@link MutateHistory} — replace conversation history.
 *   <li>{@link MutateText} — replace user-message text.
 *   <li>{@link MutateResult} — replace tool-result output.
 *   <li>{@link Block} — refuse the action, emit a blocked event.
 *   <li>{@link Inject} — inject a synthetic user message before the next turn.
 *   <li>{@link Stop} — terminate the session with the given result string.
 * </ul>
 */
public sealed interface HookOutcome
    permits HookOutcome.OnUserMessage,
        HookOutcome.PreModelTurn,
        HookOutcome.PostModelTurn,
        HookOutcome.PreStop,
        HookOutcome.PreToolUse,
        HookOutcome.PostToolUse {

  /** An outcome an {@link OnUserMessageHook} may return. */
  sealed interface OnUserMessage extends HookOutcome permits Continue, MutateText, Block, Stop {}

  /** An outcome a {@link PreModelTurnHook} may return. */
  sealed interface PreModelTurn extends HookOutcome permits Continue, MutateHistory, Inject, Stop {}

  /** An outcome a {@link PostModelTurnHook} may return. */
  sealed interface PostModelTurn extends HookOutcome permits Continue, Inject, Stop {}

  /** An outcome a {@link PreStopHook} may return. */
  sealed interface PreStop extends HookOutcome permits Continue, Inject, Stop {}

  /** An outcome a {@link PreToolUseHook} may return. */
  sealed interface PreToolUse extends HookOutcome
      permits Continue, MutateArgs, Block, Inject, Stop {}

  /** An outcome a {@link PostToolUseHook} may return. */
  sealed interface PostToolUse extends HookOutcome permits Continue, MutateResult, Inject, Stop {}

  /**
   * Singleton {@link Continue} outcome. Cheaper than allocating a fresh record per hook fire.
   *
   * @return the shared continue value
   */
  static Continue cont() {
    return Continue.INSTANCE;
  }

  /**
   * Replace tool arguments at {@link PreToolUseHook}.
   *
   * @param args the replacement arguments; non-null
   * @return a fresh {@link MutateArgs}
   * @throws NullPointerException if {@code args} is null
   */
  static MutateArgs mutateArgs(Map<String, Object> args) {
    return new MutateArgs(args);
  }

  /**
   * Replace conversation history at {@link PreModelTurnHook}.
   *
   * @param history the replacement history; non-null (may be empty)
   * @return a fresh {@link MutateHistory}
   * @throws NullPointerException if {@code history} is null
   */
  static MutateHistory mutateHistory(List<Message> history) {
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
  static MutateText mutateText(String text) {
    return new MutateText(text);
  }

  /**
   * Replace tool-result output at {@link PostToolUseHook}.
   *
   * @param output the replacement output; non-null (may be empty)
   * @return a fresh {@link MutateResult}
   * @throws NullPointerException if {@code output} is null
   */
  static MutateResult mutateResult(String output) {
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
  static Block block(String reason) {
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
  static Inject inject(String userMessage) {
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
  static Stop stop(String result) {
    return new Stop(result);
  }

  /** Proceed with the action the hook was inspecting. */
  record Continue()
      implements OnUserMessage, PreModelTurn, PostModelTurn, PreStop, PreToolUse, PostToolUse {

    static final Continue INSTANCE = new Continue();
  }

  /**
   * Replace tool arguments at {@link PreToolUseHook}; the next hook of the chain sees the call with
   * these arguments. Arguments {@link JsonSnapshot} rejects throw {@link IllegalArgumentException};
   * thrown from a hook, like any exception, that refuses the call.
   *
   * @param args the replacement arguments; non-null, a JSON object snapshotted as {@link
   *     JsonSnapshot} defines it
   */
  record MutateArgs(Map<String, Object> args) implements PreToolUse {

    public MutateArgs {
      Objects.requireNonNull(args, "args must not be null");
      args = JsonSnapshot.ofObject("args", args);
    }
  }

  /**
   * Replace conversation history at {@link PreModelTurnHook}.
   *
   * @param history the replacement history; non-null, defensively copied (may be empty)
   */
  record MutateHistory(List<Message> history) implements PreModelTurn {

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
  record MutateText(String text) implements OnUserMessage {

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
  record MutateResult(String output) implements PostToolUse {

    public MutateResult {
      Objects.requireNonNull(output, "output must not be null");
    }
  }

  /**
   * Refuse the action and surface a human-readable reason.
   *
   * @param reason non-blank reason text
   */
  record Block(String reason) implements OnUserMessage, PreToolUse {

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
  record Inject(String userMessage)
      implements PreModelTurn, PostModelTurn, PreStop, PreToolUse, PostToolUse {

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
  record Stop(String result)
      implements OnUserMessage, PreModelTurn, PostModelTurn, PreStop, PreToolUse, PostToolUse {

    public Stop {
      Objects.requireNonNull(result, "result must not be null");
      if (Strings.isBlank(result)) {
        throw new IllegalArgumentException("result must not be blank");
      }
    }
  }
}
