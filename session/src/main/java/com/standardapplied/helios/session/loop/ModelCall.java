/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.TransientStreamException;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.schema.StructuredOutputParseException;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.SerializedError;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.StreamRetryPolicy;
import com.standardapplied.helios.session.UserMessage;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The model call of one turn: streams the response with bounded retry on {@link
 * TransientStreamException}, and recovers a structured-output parse failure by putting the wrong
 * attempt in history and asking the model to correct it.
 *
 * <p>Each attempt reads the turn's stream on a virtual thread of its own, {@code
 * helios-model-stream-<sessionId>}, while the loop thread waits for it; the run's cancellation
 * closes the stream. When the session has an {@link OutputSchema}, every call goes through {@link
 * Model#chatStream(List, List, OutputSchema)} so the schema rides the provider's native channel; it
 * is dormant on tool-calling turns and activates on text-output turns.
 */
final class ModelCall {

  private static final Logger LOGGER = Logger.getLogger(TurnRunner.class.getName());

  private static final String CANCELLED = "session cancelled while waiting for model stream";

  private final LoopCollaborators collaborators;
  private final EventEmitter emitter;
  private final UsageAccounting accounting;
  private final Model model;
  private final OutputSchema<?> outputSchema;

  ModelCall(
      LoopCollaborators collaborators,
      EventEmitter emitter,
      UsageAccounting accounting,
      Model model,
      OutputSchema<?> outputSchema) {
    this.collaborators = collaborators;
    this.emitter = emitter;
    this.accounting = accounting;
    this.model = model;
    this.outputSchema = outputSchema;
  }

  /**
   * Stream the model's response, retrying under {@link SessionLimits#streamRetryPolicy()}. Each
   * failed attempt emits a {@link QueryEvent.TurnRetried} carrying the next back-off delay: the
   * failure's {@link TransientStreamException#retryAfter()} capped by the policy's {@link
   * com.standardapplied.helios.core.fault.Backoff#maxDelay()}, or else the policy's {@link
   * StreamRetryPolicy#nextDelay(int)}; the back-off sleep honours the session's {@link
   * CancellationToken} so wall-clock / explicit-cancel terminations abort cleanly without burning
   * the full delay. A cancelled session or an interrupted thread starts no further attempt. The
   * caller maps a still-failing transient error to {@link
   * com.standardapplied.helios.session.ResultMessage.ErrorTransientStream} via {@link
   * StopClassifier}.
   *
   * <p>Every attempt is reported once through {@link UsageAccounting}, when it is known whether it
   * is retried: the final attempt is accounted, a retried one is reported with zero usage once its
   * back-off elapsed, so an attempt whose back-off the run's cancellation cuts short is the final
   * one and is accounted; an attempt a host {@link Error} ended is reported with zero usage.
   *
   * <p>Non-transient throwables ({@link StructuredOutputParseException}, the provider's exception
   * of an idle stream, or any other unchecked exception opening or reading the stream) end the
   * attempts immediately — only the type guard on {@code TransientStreamException} triggers a
   * retry.
   *
   * @return the final attempt's outcome, carrying the number of attempts made
   * @throws Error an {@link Error} the turn ended with, as itself
   */
  TurnOutcome stream(SessionState state, SessionLimits limits, List<Tool> visibleTools) {
    var policy = limits.streamRetryPolicy();
    var clock = collaborators.clock();
    var attempt = 0;
    while (true) {
      attempt++;
      var started = clock.instant();
      TurnOutcome turn;
      try {
        turn = streamOnce(state, limits, visibleTools);
      } catch (Error failure) {
        accounting.reportUnaccounted(state, attempt, failure, started, clock.instant());
        throw failure;
      }
      var ended = clock.instant();
      if (!(turn.streamError() instanceof TransientStreamException transientErr)
          || attempt >= policy.maxAttempts()
          || state.cancellation().isCancelled()
          || Thread.currentThread().isInterrupted()) {
        accounting.accountTurn(state, turn, attempt, started, ended);
        return turn.withStreamAttempts(attempt);
      }
      var backoff = delay(policy, attempt, transientErr);
      emitter.emit(
          state,
          new QueryEvent.TurnRetried(
              state.sessionId(),
              state.runId(),
              state.currentTurnIndex(),
              collaborators.clock().instant(),
              attempt,
              backoff,
              transientErr.providerName(),
              SerializedError.of(transientErr).redactedBy(collaborators.secrets().redactor())));
      if (!sleepHonouringCancellation(backoff, state.cancellation())) {
        accounting.accountTurn(state, turn, attempt, started, ended);
        return turn.withStreamAttempts(attempt);
      }
      accounting.reportUnaccounted(state, attempt, transientErr, started, ended);
    }
  }

  private static Duration delay(
      StreamRetryPolicy policy, int failedAttempt, TransientStreamException failure) {
    var cap = policy.backoff().maxDelay();
    return failure
        .retryAfter()
        .map(wait -> wait.compareTo(cap) < 0 ? wait : cap)
        .orElseGet(() -> policy.nextDelay(failedAttempt));
  }

  /**
   * One attempt. What opening the stream throws ends the turn; an {@link Error} also ends the
   * session ({@link TurnReader}), and one the turn ended with is thrown as itself, so it leaves the
   * loop as a tool's or a hook's does.
   */
  private TurnOutcome streamOnce(SessionState state, SessionLimits limits, List<Tool> tools) {
    var reader = new TurnReader(state, emitter, collaborators.clock());
    CloseableIterator<ModelChunk> stream;
    try {
      var history = state.history().snapshot();
      stream =
          outputSchema != null
              ? model.chatStream(history, tools, outputSchema)
              : model.chatStream(history, tools);
    } catch (Throwable t) {
      reader.end(t);
      return thrownIfError(reader.outcome());
    }
    return thrownIfError(read(state, limits, reader, stream));
  }

  private static TurnOutcome thrownIfError(TurnOutcome turn) {
    if (turn.streamError() instanceof Error error) {
      throw error;
    }
    return turn;
  }

  /**
   * Reads {@code stream} on a virtual thread of its own while this thread waits for the turn. The
   * reader, which closes the stream as it ends, is then waited for at most {@link
   * SessionLimits#stopGrace()}, so what its close throws is recorded before the turn's outcome is
   * acted on.
   */
  private TurnOutcome read(
      SessionState state,
      SessionLimits limits,
      TurnReader reader,
      CloseableIterator<ModelChunk> stream) {
    var registration =
        state
            .cancellation()
            .onCancel(
                () -> {
                  reader.end(new CancellationException(CANCELLED));
                  closeAside(state, reader, stream);
                });
    var thread =
        Thread.ofVirtual()
            .name("helios-model-stream-" + state.sessionId())
            .start(() -> reader.read(stream));
    var interrupted = false;
    try {
      reader.await();
    } catch (InterruptedException e) {
      interrupted = true;
      reader.end(e);
      closeAside(state, reader, stream);
    } finally {
      registration.remove();
    }
    awaitReader(state.sessionId(), thread, limits.stopGrace());
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
    return reader.outcome();
  }

  /**
   * Closes {@code stream} on a thread of its own, so a close that blocks holds up no caller: not
   * the loop thread, which registers its cancel callback after the session may already be cancelled
   * and then runs it itself.
   */
  private static void closeAside(
      SessionState state, TurnReader reader, CloseableIterator<ModelChunk> stream) {
    Thread.ofVirtual()
        .name("helios-model-stream-close-" + state.sessionId())
        .start(() -> reader.close(stream));
  }

  private static void awaitReader(String sessionId, Thread reader, Duration grace) {
    try {
      if (reader.join(grace)) {
        return;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    LOGGER.warning(
        () ->
            "the model stream of session "
                + sessionId
                + " did not end within "
                + grace
                + " of its close; it is not known to be closed");
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
  boolean selfCorrectSchema(SessionState state, TurnOutcome turn) {
    if (!(turn.streamError() instanceof StructuredOutputParseException parseFailure)) {
      return false;
    }
    var wrongAttempt = parseFailure.rawContent();
    if (Strings.isEmpty(wrongAttempt)) {
      // Real streaming providers may surface text deltas before the error fires; the reader
      // has accumulated them even when the exception itself didn't carry rawContent.
      wrongAttempt = turn.assistantContent();
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
   * <p>Implementation: a one-shot {@link CountDownLatch} the cancellation callback counts down. The
   * callback registration is removed in {@code finally} so a long-lived session token does not
   * accumulate dead callbacks across many retries.
   *
   * <p>{@code delay} is contractually non-null and non-negative — the policy's back-off is bounded
   * by {@link com.standardapplied.helios.core.fault.Backoff}'s validation and a {@code Retry-After}
   * by {@link TransientStreamException}'s. Zero short-circuits the latch path; the caller's
   * subsequent retry attempt re-checks cancellation before issuing the next request.
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
      return !latch.await(TimeUnit.NANOSECONDS.convert(delay), TimeUnit.NANOSECONDS)
          && !token.isCancelled();
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      return false;
    } finally {
      registration.remove();
    }
  }
}
