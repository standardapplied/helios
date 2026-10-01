/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.loop;

import ai.singlr.core.model.Citation;
import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.ModelChunk;
import ai.singlr.core.model.Response.Usage;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.session.QueryEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Translates a model's {@link ModelChunk} stream into per-turn {@link QueryEvent}s plus an
 * accumulated {@link TurnOutcome}.
 *
 * <p>Created once per model turn by {@link TurnRunner}. The subscriber appends to an internal
 * {@code StringBuilder} for text deltas, collects tool calls, records the final {@link Usage} and
 * metadata, and exposes a {@link CountDownLatch} the runner blocks on until {@link #onComplete()}
 * or {@link #onError(Throwable)} fires.
 *
 * <h2>Thread-safety</h2>
 *
 * The producer (the provider's streaming publisher) calls {@code onSubscribe/onNext/onError/
 * onComplete} on its own thread; the consumer (the runner thread) reads {@link #toOutcome()} and
 * {@link #toolCalls()} after {@link #awaitDone()} returns. The barrier between the two phases makes
 * the StringBuilder + List access safe via the latch's happens-before edge; the atomic fields cover
 * the case where {@code awaitDone} is interrupted before the producer's terminal signal fires.
 */
final class TurnSubscriber implements Flow.Subscriber<ModelChunk> {

  private final SessionState state;
  private final EventEmitter emitter;
  private final Clock clock;
  private final ScheduledExecutorService scheduler;
  private final Duration idleTimeout;
  private final long idleTimeoutMillis;
  private final StringBuilder content = new StringBuilder();
  private final List<ToolCall> toolCalls = new CopyOnWriteArrayList<>();
  private final CountDownLatch done = new CountDownLatch(1);
  private final AtomicReference<FinishReason> finishReason =
      new AtomicReference<>(FinishReason.STOP);
  private final AtomicReference<Usage> usage = new AtomicReference<>(Usage.of(0, 0));
  private final AtomicReference<Map<String, String>> metadata = new AtomicReference<>(Map.of());
  private final AtomicReference<List<Citation>> citations = new AtomicReference<>(List.of());
  private final AtomicReference<Throwable> error = new AtomicReference<>();
  private final AtomicReference<ScheduledFuture<?>> idleTimer = new AtomicReference<>();

  TurnSubscriber(
      SessionState state,
      EventEmitter emitter,
      Clock clock,
      ScheduledExecutorService scheduler,
      Duration idleTimeout) {
    this.state = state;
    this.emitter = emitter;
    this.clock = clock;
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
    this.idleTimeout = Objects.requireNonNull(idleTimeout, "idleTimeout must not be null");
    this.idleTimeoutMillis = idleTimeout.toMillis();
  }

  @Override
  public void onSubscribe(Flow.Subscription subscription) {
    armIdleTimer();
    subscription.request(Long.MAX_VALUE);
  }

  @Override
  public void onNext(ModelChunk chunk) {
    armIdleTimer();
    switch (chunk) {
      case ModelChunk.TextDelta(String text) -> handleTextDelta(text);
      case ModelChunk.ThinkingDelta(String text) -> handleThinkingDelta(text);
      case ModelChunk.ToolUseStop(ToolCall call) -> toolCalls.add(call);
      case ModelChunk.MessageStop ms -> handleMessageStop(ms);
      case ModelChunk.UsageDelta ignored -> {}
      case ModelChunk.ToolUseStart ignored -> {}
      case ModelChunk.ToolUseDelta ignored -> {}
    }
  }

  /**
   * Cancel any pending idle-deadline task and arm a fresh one. Called on subscribe and on every
   * inbound chunk; a stream that emits no chunk for {@code idleTimeout} fires {@link
   * #fireIdleTimeout()} which surfaces the stall to the runner as a turn-ending error.
   *
   * <p>Two race-safety guards: after detaching the prior task we check {@link CountDownLatch}
   * before scheduling a fresh one — if a terminal signal already fired we skip re-arming — and the
   * post-schedule {@code compareAndSet} cancels the fresh task on the rare path where a concurrent
   * arm beat us to slot it. Either guard losing leaks neither a scheduler task nor a missed
   * deadline.
   */
  private void armIdleTimer() {
    var prior = idleTimer.getAndSet(null);
    if (prior != null) {
      prior.cancel(false);
    }
    if (done.getCount() == 0L) {
      return;
    }
    var fresh = scheduler.schedule(this::fireIdleTimeout, idleTimeoutMillis, TimeUnit.MILLISECONDS);
    if (!idleTimer.compareAndSet(null, fresh)) {
      fresh.cancel(false);
    }
  }

  private void cancelIdleTimer() {
    var t = idleTimer.getAndSet(null);
    if (t != null) {
      t.cancel(false);
    }
  }

  private void fireIdleTimeout() {
    error.compareAndSet(
        null,
        new TimeoutException(
            "model stream emitted no chunk for "
                + idleTimeout
                + " (streamIdleTimeout); treating as stalled"));
    finishReason.set(FinishReason.ERROR);
    done.countDown();
  }

  private void handleTextDelta(String text) {
    content.append(text);
    emitter.emit(
        state,
        new QueryEvent.AssistantText(state.sessionId(), state.currentTurnIndex(), now(), text));
  }

  private void handleThinkingDelta(String text) {
    emitter.emit(
        state,
        new QueryEvent.AssistantThinking(
            state.sessionId(), state.currentTurnIndex(), now(), text, ""));
  }

  private void handleMessageStop(ModelChunk.MessageStop chunk) {
    finishReason.set(parseFinishReason(chunk.stopReason()));
    usage.set(chunk.usage());
    metadata.set(chunk.metadata());
    var turnCitations = chunk.citations();
    citations.set(turnCitations);
    if (!turnCitations.isEmpty()) {
      emitter.emit(
          state,
          new QueryEvent.AssistantCitations(
              state.sessionId(), state.currentTurnIndex(), now(), turnCitations));
    }
  }

  @Override
  public void onError(Throwable t) {
    cancelIdleTimer();
    error.set(t);
    finishReason.set(FinishReason.ERROR);
    done.countDown();
  }

  @Override
  public void onComplete() {
    cancelIdleTimer();
    done.countDown();
  }

  /**
   * Block until the producer's terminal signal fires, the session's {@link CancellationToken} is
   * cancelled, or the calling thread is interrupted. Without the cancellation hook a provider
   * stream that never delivers {@code onComplete} / {@code onError} (silent socket, hung proxy)
   * would pin this thread indefinitely — defeating {@link
   * ai.singlr.session.SessionLimits#maxWallClock()} which is only re-checked at turn boundaries.
   *
   * <p>The cancellation callback is removed in {@code finally} so a long-lived session token does
   * not accumulate one stale registration per turn (see the {@code CancellationToken.onCancel}
   * cleanup contract).
   */
  void awaitDone(CancellationToken cancellation) {
    var registration = cancellation.onCancel(this::cancelFromToken);
    try {
      done.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      error.compareAndSet(null, e);
      finishReason.set(FinishReason.ERROR);
    } finally {
      registration.remove();
      cancelIdleTimer();
    }
  }

  private void cancelFromToken() {
    error.compareAndSet(
        null, new CancellationException("session cancelled while waiting for model stream"));
    finishReason.set(FinishReason.ERROR);
    done.countDown();
  }

  TurnOutcome toOutcome() {
    return toOutcome(1);
  }

  /**
   * Build the turn outcome with an explicit {@code streamAttempts} count. Used by {@link
   * TurnRunner} when retrying a {@link ai.singlr.core.model.TransientStreamException}; the count
   * surfaces on {@link ai.singlr.session.ResultMessage.ErrorTransientStream} when the retry budget
   * is exhausted.
   *
   * <p>The throwable recorded by {@link #onError(Throwable)} is carried through unchanged so
   * downstream consumers ({@link StopClassifier}, observability listeners) can walk the full cause
   * chain instead of seeing only the wrapper's {@code getMessage()}.
   *
   * @param streamAttempts the attempt count to record on the outcome; must be {@code >= 1}
   * @return a {@link TurnOutcome} populated from the current accumulator state
   */
  TurnOutcome toOutcome(int streamAttempts) {
    var err = error.get();
    var assistantContent =
        err != null
            ? (err.getMessage() == null ? err.getClass().getSimpleName() : err.getMessage())
            : content.toString();
    return new TurnOutcome(
        finishReason.get(), assistantContent, usage.get(), metadata.get(), err, streamAttempts);
  }

  List<ToolCall> toolCalls() {
    return new ArrayList<>(toolCalls);
  }

  /**
   * The grounding citations carried on this turn's {@link ModelChunk.MessageStop}, or an empty list
   * when the turn did no grounding. Read by {@link TurnRunner} after {@link
   * #awaitDone(CancellationToken)} to accumulate into the session totals.
   *
   * @return the turn's citations; never null, immutable, may be empty
   */
  List<Citation> citations() {
    return citations.get();
  }

  /**
   * The terminal error captured by {@link #onError(Throwable)}, or {@code null} when the stream
   * completed normally. Used by {@link TurnRunner} to inspect for recoverable conditions (e.g.
   * {@link ai.singlr.core.schema.StructuredOutputParseException}) before letting the {@link
   * ai.singlr.core.model.FinishReason#ERROR} verdict propagate to the {@link StopClassifier}.
   *
   * @return the error, or {@code null}
   */
  Throwable error() {
    return error.get();
  }

  /**
   * The text accumulated by {@link ModelChunk.TextDelta} events. Used by {@link TurnRunner} when
   * {@link #error()} is set, so the assistant's pre-error tokens (if any) can be preserved in
   * history alongside the corrective synthetic user message.
   *
   * @return the accumulated text; never null
   */
  String accumulatedContent() {
    return content.toString();
  }

  private Instant now() {
    return clock.instant();
  }

  private static FinishReason parseFinishReason(String stopReason) {
    try {
      return FinishReason.valueOf(stopReason);
    } catch (IllegalArgumentException ignored) {
      return FinishReason.STOP;
    }
  }
}
