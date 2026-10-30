/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session;

import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.common.JsonSnapshot;
import com.standardapplied.helios.core.common.OrderedMaps;
import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.model.Citation;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.session.ask.AskUserQuestionRequest;
import com.standardapplied.helios.session.ask.AskUserQuestionResponse;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.HookPhase;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Streamed event from an agent session.
 *
 * <p>Sealed; pattern-matched by subscribers (HTTP SSE, audit sink, the session's own trace
 * recorder, custom UI) to surface assistant text, tool activity, context-editing decisions, and
 * lifecycle transitions. Subscribers branch on the subtype via {@code switch}; they never parse
 * strings.
 *
 * <p>Every subtype carries the same four common fields ({@code sessionId}, {@code runId}, {@code
 * turnIndex}, {@code timestamp}) plus subtype-specific detail. Common-field validation lives in
 * {@link #validateCommon(String, String, long, Instant)} so the record bodies stay tight. A session
 * is one stream of events across its runs: {@link RunStarted} and {@link RunEnded} bracket each
 * run, and every event names the run it belongs to, so the events of one run are a filter on {@code
 * runId}.
 *
 * <p>The subtypes cover every observable lifecycle event the agent loop emits — run boundaries,
 * assistant output (text, thinking, grounding citations), user input, model calls ({@link
 * ModelCallEnded}), context lifecycle, tool dispatch, hook activity ({@link HookFired} carries the
 * deciding hook's phase and its redacted outcome), and terminal results.
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
        QueryEvent.ModelCallEnded,
        QueryEvent.RunStarted,
        QueryEvent.RunEnded,
        QueryEvent.Error {

  /**
   * The stable identifier of the session that produced this event.
   *
   * @return non-blank session id
   */
  String sessionId();

  /**
   * The identifier of the run that produced this event.
   *
   * @return non-blank run id
   */
  String runId();

  /**
   * The turn index of the run in which this event occurred; {@code 0} before the run's first turn.
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
   * Validate the four fields every subtype shares.
   *
   * @throws NullPointerException if {@code sessionId}, {@code runId} or {@code timestamp} is null
   * @throws IllegalArgumentException if {@code sessionId} or {@code runId} is blank or {@code
   *     turnIndex} is negative
   */
  static void validateCommon(String sessionId, String runId, long turnIndex, Instant timestamp) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    if (Strings.isBlank(sessionId)) {
      throw new IllegalArgumentException("sessionId must not be blank");
    }
    Objects.requireNonNull(runId, "runId must not be null");
    if (Strings.isBlank(runId)) {
      throw new IllegalArgumentException("runId must not be blank");
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
   * message. A {@link TurnRetried} discards the failed attempt: the next attempt streams the
   * message again from its start, so a subscriber restarts the turn's text there.
   *
   * @param sessionId the session id
   * @param runId the run id
   * @param turnIndex the turn index
   * @param timestamp the event timestamp
   * @param text the text delta; non-null, may be empty
   */
  record AssistantText(
      String sessionId, String runId, long turnIndex, Instant timestamp, String text)
      implements QueryEvent {

    public AssistantText {
      validateCommon(sessionId, runId, turnIndex, timestamp);
      Objects.requireNonNull(text, "text must not be null");
    }
  }

  /**
   * Assistant produced a reasoning / thinking delta (extended-thinking models only). Like {@link
   * AssistantText}, a {@link TurnRetried} restarts the turn's thinking. The turn's thinking
   * signatures are not here: they are in the history {@link
   * com.standardapplied.helios.core.model.Message#metadata()} of its assistant message, under the
   * provider's key, {@code anthropic.thinkingBlocks} (text and signature per block) or {@code
   * gemini.thoughtSignatures}; OpenAI signs no summary.
   *
   * @param sessionId the session id
   * @param runId the run id
   * @param turnIndex the turn index
   * @param timestamp the event timestamp
   * @param text the thinking text delta; non-null, may be empty
   */
  record AssistantThinking(
      String sessionId, String runId, long turnIndex, Instant timestamp, String text)
      implements QueryEvent {

    public AssistantThinking {
      validateCommon(sessionId, runId, turnIndex, timestamp);
      Objects.requireNonNull(text, "text must not be null");
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
   * @param runId the run id
   * @param turnIndex the turn index that produced the citations
   * @param timestamp the event timestamp
   * @param citations the turn's grounding citations; non-null, non-empty, defensively copied
   */
  record AssistantCitations(
      String sessionId, String runId, long turnIndex, Instant timestamp, List<Citation> citations)
      implements QueryEvent {

    public AssistantCitations {
      validateCommon(sessionId, runId, turnIndex, timestamp);
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
   * @param runId the run id
   * @param turnIndex the turn index in which the message will be processed
   * @param timestamp the event timestamp
   * @param message the user's message
   */
  record UserMessageReceived(
      String sessionId, String runId, long turnIndex, Instant timestamp, UserMessage message)
      implements QueryEvent {

    public UserMessageReceived {
      validateCommon(sessionId, runId, turnIndex, timestamp);
      Objects.requireNonNull(message, "message must not be null");
    }
  }

  /**
   * An {@link com.standardapplied.helios.session.hooks.OnUserMessageHook OnUserMessageHook} dropped
   * a user message via {@link com.standardapplied.helios.session.hooks.HookOutcome.Block}. The
   * message never reaches the model; the loop continues with whatever else was queued, or stays
   * idle until the next {@code send}.
   *
   * <p>Subscribers (UIs, audit logs) use this event to surface the drop — without it, a blocked
   * message looks identical to a user who never sent anything.
   *
   * @param sessionId the session id
   * @param runId the run id
   * @param turnIndex the turn index the message would have been processed in
   * @param timestamp the event timestamp
   * @param message the dropped message; non-null
   * @param hookName the name of the hook that blocked the message; non-null and non-blank
   * @param reason the reason the hook supplied; non-null and non-blank
   */
  record MessageBlocked(
      String sessionId,
      String runId,
      long turnIndex,
      Instant timestamp,
      UserMessage message,
      String hookName,
      String reason)
      implements QueryEvent {

    public MessageBlocked {
      validateCommon(sessionId, runId, turnIndex, timestamp);
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
   * @param runId the run id
   * @param turnIndex the turn index
   * @param timestamp the event timestamp
   * @param usagePct fraction of the context window consumed; non-negative
   */
  record ContextWarning(
      String sessionId, String runId, long turnIndex, Instant timestamp, double usagePct)
      implements QueryEvent {

    public ContextWarning {
      validateCommon(sessionId, runId, turnIndex, timestamp);
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
   * @param runId the run id
   * @param turnIndex the turn index
   * @param timestamp the event timestamp
   * @param removedBlocks count of messages removed or collapsed; non-negative
   * @param tokensBefore estimated tokens before compaction; non-negative
   * @param tokensAfter estimated tokens after compaction; non-negative
   */
  record ContextEdited(
      String sessionId,
      String runId,
      long turnIndex,
      Instant timestamp,
      int removedBlocks,
      long tokensBefore,
      long tokensAfter)
      implements QueryEvent {

    public ContextEdited {
      validateCommon(sessionId, runId, turnIndex, timestamp);
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
   * (end-of-turn followed by a {@code RunEnded}).
   *
   * @param sessionId the session id
   * @param runId the run id
   * @param turnIndex the turn index that ended
   * @param timestamp the event timestamp
   * @param reason the turn's finish reason
   */
  record TurnEnded(
      String sessionId, String runId, long turnIndex, Instant timestamp, FinishReason reason)
      implements QueryEvent {

    public TurnEnded {
      validateCommon(sessionId, runId, turnIndex, timestamp);
      Objects.requireNonNull(reason, "reason must not be null");
    }
  }

  /**
   * A model-stream attempt failed with a {@link
   * com.standardapplied.helios.core.model.TransientStreamException} and the agent loop is about to
   * retry the turn: a transport failure, a 408, 429 or 5xx answer to the stream request (the
   * error's {@code cause()} is the provider's exception, whose message starts {@code API error
   * (status <n>)}), or an error event the provider marks retryable mid-stream. Fires after each
   * failed attempt, before the configured back-off delay; the next attempt is the {@code
   * attemptNumber + 1}-th overall. Subscribers can use this to surface "retrying…" UI affordances
   * and to correlate per-attempt token usage in their own ledgers. The {@link AssistantText} and
   * {@link AssistantThinking} deltas the failed attempt streamed are superseded: the next attempt
   * streams its answer from the start.
   *
   * <p>Does <em>not</em> fire on the final exhausted attempt — that one terminates the session via
   * {@link ResultMessage.ErrorTransientStream} which is surfaced through the subsequent {@link
   * RunEnded} event.
   *
   * @param sessionId the session id
   * @param runId the run id
   * @param turnIndex the turn index that is being retried
   * @param timestamp the event timestamp (captured before the back-off sleep begins)
   * @param attemptNumber the 1-based attempt number that just failed; the next attempt is {@code
   *     attemptNumber + 1}
   * @param backoff the wall-clock delay the loop will wait before issuing the next attempt: the
   *     response's {@code Retry-After} capped by the policy back-off's {@code maxDelay()} when the
   *     response carried one, the policy's {@code nextDelay} otherwise; non-null and non-negative
   * @param providerName the provider's short identifier carried from {@link
   *     com.standardapplied.helios.core.model.TransientStreamException#providerName()}; non-blank
   * @param error the serialised throwable that caused this attempt to fail, with cause chain intact
   */
  record TurnRetried(
      String sessionId,
      String runId,
      long turnIndex,
      Instant timestamp,
      int attemptNumber,
      Duration backoff,
      String providerName,
      SerializedError error)
      implements QueryEvent {

    public TurnRetried {
      validateCommon(sessionId, runId, turnIndex, timestamp);
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
   * A model attempt ended: one event per attempt, a compactor's summary call included. An attempt
   * the session accounts (a turn's final attempt, which a schema self-correction also is, and a
   * compactor's call that reports tokens) carries exactly the usage it added to the run's totals
   * and the cost computed for that usage, so the events' usage sums to the run's; an attempt that
   * failed transiently and is retried carries zero usage and cost, and follows its {@link
   * TurnRetried} once the back-off elapsed. An attempt a host {@link java.lang.Error} ended has
   * none. A cost calculator that fails leaves the call's usage accounted at zero cost.
   *
   * @param sessionId the session id
   * @param runId the run id
   * @param turnIndex the turn index of the call
   * @param timestamp when the attempt ended
   * @param modelId the model id the usage was priced at; non-null
   * @param attempt the 1-based attempt number within the turn; a compactor's call is attempt 1
   * @param finishReason the stream's finish reason, {@code ERROR} for a failed attempt and {@code
   *     STOP} for a compactor's call; non-null
   * @param usage the usage the attempt added to the run's totals; non-null
   * @param cost the cost of {@code usage}; non-null
   * @param duration from the attempt's request to its stream's end, or across the compactor's call,
   *     on the session's clock; non-null, non-negative
   * @param error the attempt's error, redacted against the session's secret registry; null when the
   *     attempt succeeded
   */
  record ModelCallEnded(
      String sessionId,
      String runId,
      long turnIndex,
      Instant timestamp,
      String modelId,
      int attempt,
      FinishReason finishReason,
      Usage usage,
      CostEstimate cost,
      Duration duration,
      SerializedError error)
      implements QueryEvent {

    public ModelCallEnded {
      validateCommon(sessionId, runId, turnIndex, timestamp);
      Objects.requireNonNull(modelId, "modelId must not be null");
      if (attempt < 1) {
        throw new IllegalArgumentException("attempt must be >= 1, got " + attempt);
      }
      Objects.requireNonNull(finishReason, "finishReason must not be null");
      Objects.requireNonNull(usage, "usage must not be null");
      Objects.requireNonNull(cost, "cost must not be null");
      Objects.requireNonNull(duration, "duration must not be null");
      if (duration.isNegative()) {
        throw new IllegalArgumentException("duration must not be negative, got " + duration);
      }
    }
  }

  /**
   * A run started. The first event of every run, before its first {@link UserMessageReceived} or
   * {@link MessageBlocked}; its turn index is {@code 0}.
   *
   * @param sessionId the session id
   * @param runId the run id
   * @param timestamp the event timestamp
   */
  record RunStarted(String sessionId, String runId, Instant timestamp) implements QueryEvent {

    public RunStarted {
      validateCommon(sessionId, runId, 0, timestamp);
    }

    /** A run starts before its first turn. */
    @Override
    public long turnIndex() {
      return 0;
    }
  }

  /**
   * A run ended. The last event of every run, emitted before the future {@code send} returned for
   * the run completes; a run a host {@link java.lang.Error} ended emits none.
   *
   * @param sessionId the session id
   * @param runId the run id
   * @param turnIndex the run's final turn index
   * @param timestamp the event timestamp
   * @param result the run's terminal result
   */
  record RunEnded(
      String sessionId, String runId, long turnIndex, Instant timestamp, ResultMessage result)
      implements QueryEvent {

    public RunEnded {
      validateCommon(sessionId, runId, turnIndex, timestamp);
      Objects.requireNonNull(result, "result must not be null");
    }
  }

  /**
   * An error occurred during the session. May or may not be terminal — terminal errors are also
   * surfaced via a subsequent {@link RunEnded} with an error-shaped {@link ResultMessage}.
   *
   * @param sessionId the session id
   * @param runId the run id
   * @param turnIndex the turn index in which the error occurred
   * @param timestamp the event timestamp
   * @param error the serialized error
   */
  record Error(
      String sessionId, String runId, long turnIndex, Instant timestamp, SerializedError error)
      implements QueryEvent {

    public Error {
      validateCommon(sessionId, runId, turnIndex, timestamp);
      Objects.requireNonNull(error, "error must not be null");
    }
  }

  /**
   * A tool call was dispatched. Fires once per call, before the tool executes; subscribers learn
   * which tool the model invoked and with which arguments.
   *
   * @param sessionId the session id
   * @param runId the run id
   * @param turnIndex the turn index in which the call was dispatched
   * @param timestamp the event timestamp
   * @param call the assembled tool call, its arguments redacted against the session's secret
   *     registry, in keys and in string values; non-null
   */
  record ToolUse(String sessionId, String runId, long turnIndex, Instant timestamp, ToolCall call)
      implements QueryEvent {

    public ToolUse {
      validateCommon(sessionId, runId, turnIndex, timestamp);
      Objects.requireNonNull(call, "call must not be null");
    }
  }

  /**
   * A tool call completed. Carries the originating {@link ToolCall} so subscribers can correlate
   * dispatch and result without joining across events, plus the {@link
   * com.standardapplied.helios.core.tool.ToolResult ToolResult} the tool produced (success or
   * failure), its output already redacted against the session's secret registry.
   *
   * @param sessionId the session id
   * @param runId the run id
   * @param turnIndex the turn index in which the call ran
   * @param timestamp the event timestamp
   * @param call the originating call, its arguments redacted against the session's secret registry,
   *     in keys and in string values; non-null
   * @param result the tool result, its output redacted; non-null
   * @param redactions per secret name, how many occurrences the session's redaction of this result
   *     replaced, in encounter order; empty when none. Non-null, with no null key or value; copied
   */
  record ToolResult(
      String sessionId,
      String runId,
      long turnIndex,
      Instant timestamp,
      ToolCall call,
      com.standardapplied.helios.core.tool.ToolResult result,
      Map<String, Integer> redactions)
      implements QueryEvent {

    public ToolResult {
      validateCommon(sessionId, runId, turnIndex, timestamp);
      Objects.requireNonNull(call, "call must not be null");
      Objects.requireNonNull(result, "result must not be null");
      Objects.requireNonNull(redactions, "redactions must not be null");
      redactions = OrderedMaps.copyOf(redactions);
    }
  }

  /**
   * A {@link com.standardapplied.helios.session.hooks.PreToolUseHook PreToolUseHook} blocked a tool
   * call. The tool was not dispatched; the loop substitutes a synthetic tool result describing the
   * block (so the model sees its action refused with a reason).
   *
   * @param sessionId the session id
   * @param runId the run id
   * @param turnIndex the turn index in which the block fired
   * @param timestamp the event timestamp
   * @param call the call that was blocked, its arguments redacted against the session's secret
   *     registry, in keys and in string values; non-null
   * @param hookName the name of the hook that blocked the call; non-null and non-blank
   * @param reason the reason the hook supplied; non-null and non-blank
   */
  record ToolBlocked(
      String sessionId,
      String runId,
      long turnIndex,
      Instant timestamp,
      ToolCall call,
      String hookName,
      String reason)
      implements QueryEvent {

    public ToolBlocked {
      validateCommon(sessionId, runId, turnIndex, timestamp);
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
   * A {@link com.standardapplied.helios.session.hooks.PreToolUseHook PreToolUseHook} mutated a tool
   * call's arguments. The next hook of the chain sees {@code inputAfter}; the last mutation's is
   * what the call is authorized and dispatched with. Both maps are surfaced for auditability. The
   * call and both maps have every registered secret of the session redacted, in keys and in string
   * values.
   *
   * @param sessionId the session id
   * @param runId the run id
   * @param turnIndex the turn index in which the mutation fired
   * @param timestamp the event timestamp
   * @param call the call as the hook received it, redacted; non-null
   * @param hookName the name of the hook that mutated the input; non-null and non-blank
   * @param inputBefore the arguments the hook received, redacted; non-null, a JSON object
   *     snapshotted as {@link JsonSnapshot} defines it
   * @param inputAfter the replacement arguments, redacted; non-null, a JSON object snapshotted as
   *     {@link JsonSnapshot} defines it
   */
  record ToolMutated(
      String sessionId,
      String runId,
      long turnIndex,
      Instant timestamp,
      ToolCall call,
      String hookName,
      Map<String, Object> inputBefore,
      Map<String, Object> inputAfter)
      implements QueryEvent {

    public ToolMutated {
      validateCommon(sessionId, runId, turnIndex, timestamp);
      Objects.requireNonNull(call, "call must not be null");
      Objects.requireNonNull(hookName, "hookName must not be null");
      if (Strings.isBlank(hookName)) {
        throw new IllegalArgumentException("hookName must not be blank");
      }
      Objects.requireNonNull(inputBefore, "inputBefore must not be null");
      Objects.requireNonNull(inputAfter, "inputAfter must not be null");
      inputBefore = JsonSnapshot.ofObject("inputBefore", inputBefore);
      inputAfter = JsonSnapshot.ofObject("inputAfter", inputAfter);
    }
  }

  /**
   * A hook fired and produced a non-{@link HookOutcome.Continue Continue} outcome: which hook, at
   * which phase, and what it decided, with the reason or text redacted. Readers can drive UI/audit
   * off this event without polling the registry.
   *
   * <p>Continue outcomes are suppressed by design — surfacing every no-op would drown the stream.
   * Observe-only {@link com.standardapplied.helios.session.hooks.OnStreamEventHook
   * OnStreamEventHook} firings are also suppressed (the events they observe are already on the
   * stream).
   *
   * @param sessionId the session id
   * @param runId the run id
   * @param turnIndex the turn index in which the hook fired
   * @param timestamp the event timestamp
   * @param hookName the hook's name; non-null and non-blank
   * @param phase the phase the hook was bound to; non-null
   * @param outcome the outcome the hook returned, every string it carries redacted against the
   *     session's secrets ({@link HookOutcome.MutateHistory} as returned); the loop acts on the
   *     hook's own, unredacted outcome; non-null and never {@link HookOutcome.Continue}
   */
  record HookFired(
      String sessionId,
      String runId,
      long turnIndex,
      Instant timestamp,
      String hookName,
      HookPhase phase,
      HookOutcome outcome)
      implements QueryEvent {

    public HookFired {
      validateCommon(sessionId, runId, turnIndex, timestamp);
      Objects.requireNonNull(hookName, "hookName must not be null");
      if (Strings.isBlank(hookName)) {
        throw new IllegalArgumentException("hookName must not be blank");
      }
      Objects.requireNonNull(phase, "phase must not be null");
      Objects.requireNonNull(outcome, "outcome must not be null");
      if (outcome instanceof HookOutcome.Continue) {
        throw new IllegalArgumentException("a Continue outcome is never announced");
      }
    }
  }

  /**
   * The agent emitted a structured {@code AskUserQuestion} request and is awaiting an answer. The
   * host (HTTP endpoint, UI, CLI prompter) renders the question, collects the user's choice, and
   * calls {@link com.standardapplied.helios.session.AgentSession#answer(String,
   * AskUserQuestionResponse)} with the matching {@code questionId}. The agent loop blocks the
   * tool's virtual thread on the pending future until {@code answer} is called or the session is
   * cancelled.
   *
   * @param sessionId the session id
   * @param runId the run id
   * @param turnIndex the turn index in which the question was asked
   * @param timestamp the event timestamp
   * @param request the question payload; non-null
   */
  record QuestionAsked(
      String sessionId,
      String runId,
      long turnIndex,
      Instant timestamp,
      AskUserQuestionRequest request)
      implements QueryEvent {

    public QuestionAsked {
      validateCommon(sessionId, runId, turnIndex, timestamp);
      Objects.requireNonNull(request, "request must not be null");
    }
  }
}
