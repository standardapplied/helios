/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.TransientStreamException;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.schema.StructuredOutputParseException;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.SerializedError;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.UserMessage;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The model call of one turn: streams the response with bounded retry on {@link
 * TransientStreamException}, and recovers a structured-output parse failure by putting the wrong
 * attempt in history and asking the model to correct it.
 *
 * <p>When the session has an {@link OutputSchema}, every call goes through {@link
 * Model#chatStream(List, List, OutputSchema, CancellationToken)} so the schema rides the provider's
 * native channel; it is dormant on tool-calling turns and activates on text-output turns.
 */
final class ModelCall {

  private static final Logger LOGGER = Logger.getLogger(TurnRunner.class.getName());

  /** The final attempt's stream and the total number of attempts made. */
  record Attempts(StreamedTurn turn, int count) {}

  private final LoopCollaborators collaborators;
  private final EventEmitter emitter;
  private final Model model;
  private final OutputSchema<?> outputSchema;
  private final ScheduledExecutorService scheduler;

  ModelCall(
      LoopCollaborators collaborators,
      EventEmitter emitter,
      Model model,
      OutputSchema<?> outputSchema,
      ScheduledExecutorService scheduler) {
    this.collaborators = collaborators;
    this.emitter = emitter;
    this.model = model;
    this.outputSchema = outputSchema;
    this.scheduler = scheduler;
  }

  /**
   * Stream the model's response, retrying under {@link SessionLimits#streamRetryPolicy()}. Each
   * failed attempt emits a {@link QueryEvent.TurnRetried} carrying the next back-off delay; the
   * back-off sleep honours the session's {@link CancellationToken} so wall-clock / explicit-cancel
   * terminations abort cleanly without burning the full delay. The caller maps a still-failing
   * transient error to {@link
   * com.standardapplied.helios.session.ResultMessage.ErrorTransientStream} via {@link
   * StopClassifier}.
   *
   * <p>Non-transient throwables ({@link StructuredOutputParseException}, {@code
   * java.util.concurrent.TimeoutException} from the stream-idle watchdog, or any other unchecked
   * exception escaping the subscribe call) end the attempts immediately — only the type guard on
   * {@code TransientStreamException} triggers a retry.
   */
  Attempts stream(SessionState state, SessionLimits limits, List<Tool> visibleTools) {
    var policy = limits.streamRetryPolicy();
    var attempt = 0;
    while (true) {
      attempt++;
      var turn = streamOnce(state, limits, visibleTools);
      if (!(turn.error() instanceof TransientStreamException transientErr)
          || attempt >= policy.maxAttempts()
          || state.cancellation().isCancelled()) {
        return new Attempts(turn, attempt);
      }
      var backoff = policy.nextDelay(attempt);
      emitter.emit(
          state,
          new QueryEvent.TurnRetried(
              state.sessionId(),
              state.currentTurnIndex(),
              collaborators.clock().instant(),
              attempt,
              backoff,
              transientErr.providerName(),
              SerializedError.of(transientErr)));
      if (!sleepHonouringCancellation(backoff, state.cancellation())) {
        return new Attempts(turn, attempt);
      }
    }
  }

  private StreamedTurn streamOnce(SessionState state, SessionLimits limits, List<Tool> tools) {
    var subscriber =
        new TurnSubscriber(
            state, emitter, collaborators.clock(), scheduler, limits.streamIdleTimeout());
    try {
      var publisher =
          outputSchema != null
              ? model.chatStream(
                  state.history().snapshot(), tools, outputSchema, state.cancellation())
              : model.chatStream(state.history().snapshot(), tools, state.cancellation());
      publisher.subscribe(subscriber);
    } catch (Throwable t) {
      subscriber.onError(t);
    }
    return subscriber.awaitDone(state.cancellation());
  }

  /**
   * Structured-output self-correction. When the model emits JSON that is syntactically invalid,
   * doesn't match the session's configured {@link OutputSchema}, or is rejected by the schema's
   * {@link com.standardapplied.helios.core.common.SubmitValidator} ({@link
   * com.standardapplied.helios.core.schema.SubmitValidationException}), the parser raises {@link
   * StructuredOutputParseException}. Provider IO errors still terminate via {@link
   * com.standardapplied.helios.core.model.FinishReason#ERROR}.
   *
   * <p>Recovery: append the model's wrong attempt to history as an assistant message so the model
   * sees its own response through conversation context on the retry, then enqueue {@link
   * StructuredOutputParseException#correctionMessage()} as a synthetic user turn — that's the
   * field-level diff only, no rawContent echo (per the exception's class-level note on retry cost).
   * The overall retry count is bounded by {@link SessionLimits#maxTurns()} — no dedicated
   * parse-retry ceiling.
   *
   * @return {@code true} when the correction was queued and the turn ends as a tool-calls turn so
   *     the loop iterates; {@code false} when the stream's error is not a {@link
   *     StructuredOutputParseException} (or there is no error, or the steering queue rejected the
   *     correction message), so the underlying error surfaces as {@link
   *     com.standardapplied.helios.session.ResultMessage.ErrorDuringExecution}
   */
  boolean selfCorrectSchema(SessionState state, StreamedTurn turn) {
    if (!(turn.error() instanceof StructuredOutputParseException parseFailure)) {
      return false;
    }
    var wrongAttempt = parseFailure.rawContent();
    if (Strings.isEmpty(wrongAttempt)) {
      // Real streaming providers may surface text deltas before the error fires; the subscriber
      // has accumulated them even when the exception itself didn't carry rawContent.
      wrongAttempt = turn.content();
    }
    if (!wrongAttempt.isEmpty()) {
      state.history().append(Message.assistant(wrongAttempt, List.of(), Map.of()));
    }
    if (!collaborators.steeringQueue().offer(UserMessage.text(parseFailure.correctionMessage()))) {
      LOGGER.log(
          Level.WARNING,
          "schema-correction message was dropped: steering queue full; the loop will terminate"
              + " on the underlying parse error");
      return false;
    }
    return true;
  }

  /**
   * Sleep for {@code delay}, returning early when {@code cancellation} fires. Returns {@code true}
   * when the full delay elapsed (or {@code delay} was zero); {@code false} when the sleep was cut
   * short by cancellation or thread interruption.
   *
   * <p>Implementation: a one-shot {@link CountDownLatch} the cancellation callback counts down;
   * {@code latch.await(timeout)} returns {@code true} when the latch counted down (cancelled) and
   * {@code false} when the timeout elapsed (slept the full duration). The callback registration is
   * removed in {@code finally} so a long-lived session token does not accumulate dead callbacks
   * across many retries.
   *
   * <p>{@code delay} is contractually non-null and non-negative — callers pass {@link
   * com.standardapplied.helios.session.StreamRetryPolicy#nextDelay(int)} which is itself bounded by
   * {@link com.standardapplied.helios.core.fault.Backoff}'s validation. Zero short-circuits the
   * latch path; the caller's subsequent retry attempt re-checks cancellation before issuing the
   * next request.
   */
  private static boolean sleepHonouringCancellation(Duration delay, CancellationToken token) {
    if (token.isCancelled()) {
      return false;
    }
    if (delay.isZero()) {
      return true;
    }
    var latch = new CountDownLatch(1);
    var registration = token.onCancel(latch::countDown);
    try {
      boolean cancelledDuringSleep = latch.await(delay.toMillis(), TimeUnit.MILLISECONDS);
      return !cancelledDuringSleep;
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      return false;
    } finally {
      registration.remove();
    }
  }
}
