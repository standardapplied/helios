/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import ai.singlr.core.common.Strings;
import ai.singlr.core.model.Citation;
import ai.singlr.core.model.ToolCall;
import ai.singlr.session.ask.AskUserQuestionRequest;
import ai.singlr.session.ask.AskUserQuestionResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Streamed event from an agent session.
 *
 * <p>Sealed; pattern-matched by subscribers (HTTP SSE, audit sink, trace listeners, custom UI) to
 * surface assistant text, tool activity, context-editing decisions, and lifecycle transitions.
 * Subscribers branch on the subtype via {@code switch}; they never parse strings.
 *
 * <p>Every subtype carries the same three common fields ({@code sessionId}, {@code turnIndex},
 * {@code timestamp}) plus subtype-specific detail. Common-field validation lives in {@link
 * #validateCommon(String, long, Instant)} so the record bodies stay tight.
 *
 * <p>The 17 subtypes cover every observable lifecycle event the agent loop emits — assistant output
 * (text, thinking, grounding citations), user input, context lifecycle, tool dispatch, hook
 * activity, and terminal results.
 *
 * <p>Adding a subtype is a breaking change for {@code switch} consumers that lack a {@code default}
 * branch — by design, so the compiler flags consumers that need updating.
 */
public sealed interface QueryEvent
    permits QueryEvent.AssistantText,
        QueryEvent.AssistantThinking,
        QueryEvent.AssistantCitations,
        QueryEvent.UserMessageReceived,
        QueryEvent.MessageBlocked,
        QueryEvent.ContextWarning,
        QueryEvent.ContextEdited,
        QueryEvent.ToolUse,
        QueryEvent.ToolResult,
        QueryEvent.ToolBlocked,
        QueryEvent.ToolMutated,
        QueryEvent.HookFired,
        QueryEvent.QuestionAsked,
        QueryEvent.TurnEnded,
        QueryEvent.TurnRetried,
        QueryEvent.LoopEnded,
        QueryEvent.Error {

  /**
   * The stable identifier of the session that produced this event.
   *
   * @return non-blank session id
   */
  String sessionId();

  /**
   * The turn index in which this event occurred.
   *
   * @return non-negative turn index
   */
  long turnIndex();

  /**
   * The wall-clock instant at which this event was produced.
   *
   * @return non-null timestamp
   */
  Instant timestamp();

  /**
   * Validate the three fields every subtype shares.
   *
   * @throws NullPointerException if {@code sessionId} or {@code timestamp} is null
   * @throws IllegalArgumentException if {@code sessionId} is blank or {@code turnIndex} is negative
   */
  static void validateCommon(String sessionId, long turnIndex, Instant timestamp) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    if (Strings.isBlank(sessionId)) {
      throw new IllegalArgumentException("sessionId must not be blank");
    }
    if (turnIndex < 0) {
      throw new IllegalArgumentException("turnIndex must be non-negative, got " + turnIndex);
    }
    Objects.requireNonNull(timestamp, "timestamp must not be null");
  }

  /**
   * Assistant produced a token (or token batch) of plain text.
   *
   * <p>{@code text} may be empty — providers occasionally emit empty deltas during a stream.
   * Subscribers concatenate the {@code text} across the turn to assemble the full assistant
   * message.
   *
   * @param sessionId the session id
   * @param turnIndex the turn index
   * @param timestamp the event timestamp
   * @param text the text delta; non-null, may be empty
   */
  record AssistantText(String sessionId, long turnIndex, Instant timestamp, String text)
      implements QueryEvent {

    public AssistantText {
      validateCommon(sessionId, turnIndex, timestamp);
      Objects.requireNonNull(text, "text must not be null");
    }
  }

  /**
   * Assistant produced a reasoning / thinking delta (extended-thinking models only).
   *
   * @param sessionId the session id
   * @param turnIndex the turn index
   * @param timestamp the event timestamp
   * @param text the thinking text delta; non-null, may be empty
   * @param signature provider-supplied verification signature for the thinking block; non-null, may
   *     be empty
   */
  record AssistantThinking(
      String sessionId, long turnIndex, Instant timestamp, String text, String signature)
      implements QueryEvent {

    public AssistantThinking {
      validateCommon(sessionId, turnIndex, timestamp);
      Objects.requireNonNull(text, "text must not be null");
      Objects.requireNonNull(signature, "signature must not be null");
    }
  }

  /**
   * The assistant turn produced grounding citations (e.g. Google Search / web grounding). Fires
   * once per turn, at message stop, only when the turn surfaced at least one citation; turns that
   * did no grounding emit no event. The {@code citations} are this turn's citations as harvested by
   * the provider — the session's running, deduplicated total is reported separately on {@link
   * ResultMessage.Success#citations()} at termination.
   *
   * @param sessionId the session id
   * @param turnIndex the turn index that produced the citations
   * @param timestamp the event timestamp
   * @param citations the turn's grounding citations; non-null, non-empty, defensively copied
   */
  record AssistantCitations(
      String sessionId, long turnIndex, Instant timestamp, List<Citation> citations)
      implements QueryEvent {

    public AssistantCitations {
      validateCommon(sessionId, turnIndex, timestamp);
      Objects.requireNonNull(citations, "citations must not be null");
      if (citations.isEmpty()) {
        throw new IllegalArgumentException("citations must not be empty");
      }
      citations = List.copyOf(citations);
    }
  }

  /**
   * A user message arrived (via {@code send(...)} or the post-interrupt synthetic message).
   *
   * @param sessionId the session id
   * @param turnIndex the turn index in which the message will be processed
   * @param timestamp the event timestamp
   * @param message the user's message
   */
  record UserMessageReceived(
      String sessionId, long turnIndex, Instant timestamp, UserMessage message)
      implements QueryEvent {

    public UserMessageReceived {
      validateCommon(sessionId, turnIndex, timestamp);
      Objects.requireNonNull(message, "message must not be null");
    }
  }

  /**
   * An {@link ai.singlr.session.hooks.OnUserMessageHook OnUserMessageHook} dropped a user message
   * via {@link ai.singlr.session.hooks.HookOutcome.Block}. The message never reaches the model; the
   * loop continues with whatever else was queued, or stays idle until the next {@code send}.
   *
   * <p>Subscribers (UIs, audit logs) use this event to surface the drop — without it, a blocked
   * message looks identical to a user who never sent anything.
   *
   * @param sessionId the session id
   * @param turnIndex the turn index the message would have been processed in
   * @param timestamp the event timestamp
   * @param message the dropped message; non-null
   * @param hookName the name of the hook that blocked the message; non-null and non-blank
   * @param reason the reason the hook supplied; non-null and non-blank
   */
  record MessageBlocked(
      String sessionId,
      long turnIndex,
      Instant timestamp,
      UserMessage message,
      String hookName,
      String reason)
      implements QueryEvent {

    public MessageBlocked {
      validateCommon(sessionId, turnIndex, timestamp);
      Objects.requireNonNull(message, "message must not be null");
      Objects.requireNonNull(hookName, "hookName must not be null");
      if (Strings.isBlank(hookName)) {
        throw new IllegalArgumentException("hookName must not be blank");
      }
      Objects.requireNonNull(reason, "reason must not be null");
      if (Strings.isBlank(reason)) {
        throw new IllegalArgumentException("reason must not be blank");
      }
    }
  }

  /**
   * Context-window usage crossed a watermark; compaction is imminent or recommended.
   *
   * @param sessionId the session id
   * @param turnIndex the turn index
   * @param timestamp the event timestamp
   * @param usagePct fraction of the context window consumed; non-negative
   */
  record ContextWarning(String sessionId, long turnIndex, Instant timestamp, double usagePct)
      implements QueryEvent {

    public ContextWarning {
      validateCommon(sessionId, turnIndex, timestamp);
      if (usagePct < 0.0 || Double.isNaN(usagePct)) {
        throw new IllegalArgumentException(
            "usagePct must be non-negative and finite, got " + usagePct);
      }
    }
  }

  /**
   * Context compaction completed. Tokens went down (typically); blocks were dropped, summarised, or
   * pruned.
   *
   * @param sessionId the session id
   * @param turnIndex the turn index
   * @param timestamp the event timestamp
   * @param removedBlocks count of messages removed or collapsed; non-negative
   * @param tokensBefore estimated tokens before compaction; non-negative
   * @param tokensAfter estimated tokens after compaction; non-negative
   */
  record ContextEdited(
      String sessionId,
      long turnIndex,
      Instant timestamp,
      int removedBlocks,
      long tokensBefore,
      long tokensAfter)
      implements QueryEvent {

    public ContextEdited {
      validateCommon(sessionId, turnIndex, timestamp);
      if (removedBlocks < 0) {
        throw new IllegalArgumentException(
            "removedBlocks must be non-negative, got " + removedBlocks);
      }
      if (tokensBefore < 0) {
        throw new IllegalArgumentException(
            "tokensBefore must be non-negative, got " + tokensBefore);
      }
      if (tokensAfter < 0) {
        throw new IllegalArgumentException("tokensAfter must be non-negative, got " + tokensAfter);
      }
    }
  }

  /**
   * A turn ended. The {@code reason} carries why; the loop may continue (tool use) or stop
   * (end-of-turn followed by a {@code LoopEnded}).
   *
   * @param sessionId the session id
   * @param turnIndex the turn index that ended
   * @param timestamp the event timestamp
   * @param reason why this turn ended
   */
  record TurnEnded(String sessionId, long turnIndex, Instant timestamp, StopReason reason)
      implements QueryEvent {

    public TurnEnded {
      validateCommon(sessionId, turnIndex, timestamp);
      Objects.requireNonNull(reason, "reason must not be null");
    }
  }

  /**
   * A model-stream attempt failed with a recoverable transport error ({@link
   * ai.singlr.core.model.TransientStreamException}) and the agent loop is about to retry the turn.
   * Fires after each failed attempt, before the configured back-off delay; the next attempt is the
   * {@code attemptNumber + 1}-th overall. Subscribers can use this to surface "retrying…" UI
   * affordances and to correlate per-attempt token usage in their own ledgers.
   *
   * <p>Does <em>not</em> fire on the final exhausted attempt — that one terminates the session via
   * {@link ResultMessage.ErrorTransientStream} which is surfaced through the subsequent {@link
   * LoopEnded} event.
   *
   * @param sessionId the session id
   * @param turnIndex the turn index that is being retried
   * @param timestamp the event timestamp (captured before the back-off sleep begins)
   * @param attemptNumber the 1-based attempt number that just failed; the next attempt is {@code
   *     attemptNumber + 1}
   * @param backoff the wall-clock delay the loop will wait before issuing the next attempt;
   *     non-null and non-negative
   * @param providerName the provider's short identifier carried from {@link
   *     ai.singlr.core.model.TransientStreamException#providerName()}; non-blank
   * @param error the serialised throwable that caused this attempt to fail, with cause chain intact
   */
  record TurnRetried(
      String sessionId,
      long turnIndex,
      Instant timestamp,
      int attemptNumber,
      Duration backoff,
      String providerName,
      SerializedError error)
      implements QueryEvent {

    public TurnRetried {
      validateCommon(sessionId, turnIndex, timestamp);
      if (attemptNumber < 1) {
        throw new IllegalArgumentException("attemptNumber must be >= 1, got " + attemptNumber);
      }
      Objects.requireNonNull(backoff, "backoff must not be null");
      if (backoff.isNegative()) {
        throw new IllegalArgumentException("backoff must not be negative, got " + backoff);
      }
      Objects.requireNonNull(providerName, "providerName must not be null");
      if (Strings.isBlank(providerName)) {
        throw new IllegalArgumentException("providerName must not be blank");
      }
      Objects.requireNonNull(error, "error must not be null");
    }
  }

  /**
   * The session terminated. Fired exactly once per session, after all other events.
   *
   * @param sessionId the session id
   * @param turnIndex the final turn index
   * @param timestamp the event timestamp
   * @param result the terminal result message
   */
  record LoopEnded(String sessionId, long turnIndex, Instant timestamp, ResultMessage result)
      implements QueryEvent {

    public LoopEnded {
      validateCommon(sessionId, turnIndex, timestamp);
      Objects.requireNonNull(result, "result must not be null");
    }
  }

  /**
   * An error occurred during the session. May or may not be terminal — terminal errors are also
   * surfaced via a subsequent {@link LoopEnded} with an error-shaped {@link ResultMessage}.
   *
   * @param sessionId the session id
   * @param turnIndex the turn index in which the error occurred
   * @param timestamp the event timestamp
   * @param error the serialized error
   */
  record Error(String sessionId, long turnIndex, Instant timestamp, SerializedError error)
      implements QueryEvent {

    public Error {
      validateCommon(sessionId, turnIndex, timestamp);
      Objects.requireNonNull(error, "error must not be null");
    }
  }

  /**
   * A tool call was dispatched. Fires once per call, before the tool executes; subscribers learn
   * which tool the model invoked and with which arguments.
   *
   * @param sessionId the session id
   * @param turnIndex the turn index in which the call was dispatched
   * @param timestamp the event timestamp
   * @param call the assembled tool call; non-null
   */
  record ToolUse(String sessionId, long turnIndex, Instant timestamp, ToolCall call)
      implements QueryEvent {

    public ToolUse {
      validateCommon(sessionId, turnIndex, timestamp);
      Objects.requireNonNull(call, "call must not be null");
    }
  }

  /**
   * A tool call completed. Carries the originating {@link ToolCall} so subscribers can correlate
   * dispatch and result without joining across events, plus the {@link
   * ai.singlr.core.tool.ToolResult ToolResult} the tool produced (success or failure).
   *
   * @param sessionId the session id
   * @param turnIndex the turn index in which the call ran
   * @param timestamp the event timestamp
   * @param call the originating call; non-null
   * @param result the tool result; non-null
   */
  record ToolResult(
      String sessionId,
      long turnIndex,
      Instant timestamp,
      ToolCall call,
      ai.singlr.core.tool.ToolResult result)
      implements QueryEvent {

    public ToolResult {
      validateCommon(sessionId, turnIndex, timestamp);
      Objects.requireNonNull(call, "call must not be null");
      Objects.requireNonNull(result, "result must not be null");
    }
  }

  /**
   * A {@link ai.singlr.session.hooks.PreToolUseHook PreToolUseHook} blocked a tool call. The tool
   * was not dispatched; the loop substitutes a synthetic tool result describing the block (so the
   * model sees its action refused with a reason).
   *
   * @param sessionId the session id
   * @param turnIndex the turn index in which the block fired
   * @param timestamp the event timestamp
   * @param call the call that was blocked; non-null
   * @param hookName the name of the hook that blocked the call; non-null and non-blank
   * @param reason the reason the hook supplied; non-null and non-blank
   */
  record ToolBlocked(
      String sessionId,
      long turnIndex,
      Instant timestamp,
      ToolCall call,
      String hookName,
      String reason)
      implements QueryEvent {

    public ToolBlocked {
      validateCommon(sessionId, turnIndex, timestamp);
      Objects.requireNonNull(call, "call must not be null");
      Objects.requireNonNull(hookName, "hookName must not be null");
      if (Strings.isBlank(hookName)) {
        throw new IllegalArgumentException("hookName must not be blank");
      }
      Objects.requireNonNull(reason, "reason must not be null");
      if (Strings.isBlank(reason)) {
        throw new IllegalArgumentException("reason must not be blank");
      }
    }
  }

  /**
   * A {@link ai.singlr.session.hooks.PreToolUseHook PreToolUseHook} mutated a tool call's
   * arguments. The loop dispatches the tool with {@code inputAfter} instead of {@code inputBefore}.
   * Both maps are surfaced for auditability.
   *
   * @param sessionId the session id
   * @param turnIndex the turn index in which the mutation fired
   * @param timestamp the event timestamp
   * @param call the original call; non-null
   * @param hookName the name of the hook that mutated the input; non-null and non-blank
   * @param inputBefore the original arguments; non-null (defensively copied)
   * @param inputAfter the replacement arguments; non-null (defensively copied)
   */
  record ToolMutated(
      String sessionId,
      long turnIndex,
      Instant timestamp,
      ToolCall call,
      String hookName,
      Map<String, Object> inputBefore,
      Map<String, Object> inputAfter)
      implements QueryEvent {

    public ToolMutated {
      validateCommon(sessionId, turnIndex, timestamp);
      Objects.requireNonNull(call, "call must not be null");
      Objects.requireNonNull(hookName, "hookName must not be null");
      if (Strings.isBlank(hookName)) {
        throw new IllegalArgumentException("hookName must not be blank");
      }
      Objects.requireNonNull(inputBefore, "inputBefore must not be null");
      Objects.requireNonNull(inputAfter, "inputAfter must not be null");
      inputBefore = Map.copyOf(inputBefore);
      inputAfter = Map.copyOf(inputAfter);
    }
  }

  /**
   * A hook fired and produced a non-{@link ai.singlr.session.hooks.HookOutcome.Continue Continue}
   * outcome. Subscribers can drive UI/audit off this event without polling the registry.
   *
   * <p>Continue outcomes are suppressed by design — surfacing every no-op would drown the stream.
   * Observe-only {@link ai.singlr.session.hooks.OnStreamEventHook OnStreamEventHook} firings are
   * also suppressed (the events they observe are already on the stream).
   *
   * @param sessionId the session id
   * @param turnIndex the turn index in which the hook fired
   * @param timestamp the event timestamp
   * @param hookName the hook's name; non-null and non-blank
   * @param phase the lifecycle phase the hook was bound to (e.g. {@code "PreToolUseHook"});
   *     non-null and non-blank
   * @param outcomeKind the simple class name of the {@link ai.singlr.session.hooks.HookOutcome
   *     HookOutcome} subtype returned (e.g. {@code "Block"}, {@code "Inject"}); non-null and
   *     non-blank
   */
  record HookFired(
      String sessionId,
      long turnIndex,
      Instant timestamp,
      String hookName,
      String phase,
      String outcomeKind)
      implements QueryEvent {

    public HookFired {
      validateCommon(sessionId, turnIndex, timestamp);
      Objects.requireNonNull(hookName, "hookName must not be null");
      if (Strings.isBlank(hookName)) {
        throw new IllegalArgumentException("hookName must not be blank");
      }
      Objects.requireNonNull(phase, "phase must not be null");
      if (Strings.isBlank(phase)) {
        throw new IllegalArgumentException("phase must not be blank");
      }
      Objects.requireNonNull(outcomeKind, "outcomeKind must not be null");
      if (Strings.isBlank(outcomeKind)) {
        throw new IllegalArgumentException("outcomeKind must not be blank");
      }
    }
  }

  /**
   * The agent emitted a structured {@code AskUserQuestion} request and is awaiting an answer. The
   * host (HTTP endpoint, UI, CLI prompter) renders the question, collects the user's choice, and
   * calls {@link ai.singlr.session.AgentSession#answer(String, AskUserQuestionResponse)} with the
   * matching {@code questionId}. The agent loop blocks the tool's virtual thread on the pending
   * future until {@code answer} is called or the session is cancelled.
   *
   * @param sessionId the session id
   * @param turnIndex the turn index in which the question was asked
   * @param timestamp the event timestamp
   * @param request the question payload; non-null
   */
  record QuestionAsked(
      String sessionId, long turnIndex, Instant timestamp, AskUserQuestionRequest request)
      implements QueryEvent {

    public QuestionAsked {
      validateCommon(sessionId, turnIndex, timestamp);
      Objects.requireNonNull(request, "request must not be null");
    }
  }
}
