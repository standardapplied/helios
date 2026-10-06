/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.model.Citation;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.session.QueryEvent;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Translates a model's {@link ModelChunk} stream into per-turn {@link QueryEvent}s and accumulates
 * what the stream produced into a {@link StreamedTurn}.
 *
 * <p>Created once per model turn by {@link TurnRunner}. The subscriber appends to an internal
 * {@code StringBuilder} for text deltas, collects tool calls, records the final {@link Usage} and
 * metadata, and blocks the runner in {@link #awaitDone(CancellationToken)} until {@link
 * #onComplete()} or {@link #onError(Throwable)} fires. An {@link IdleWatchdog} fails a stream that
 * stalls.
 *
 * <h2>Thread-safety</h2>
 *
 * The producer (the provider's streaming publisher) calls {@code onSubscribe/onNext/onError/
 * onComplete} on its own thread; the consumer (the runner thread) receives the {@link StreamedTurn}
 * from {@link #awaitDone(CancellationToken)}. When the producer's terminal signal releases the
 * latch, its happens-before edge makes the StringBuilder and List reads safe. When the wait ends
 * another way (cancellation, idle timeout, interrupt), the producer may still be appending: the
 * atomic fields stay consistent, and the snapshot reads the error before the text, as the runner
 * did before the snapshot existed.
 */
final class TurnSubscriber implements Flow.Subscriber<ModelChunk> {

  private final SessionState state;
  private final EventEmitter emitter;
  private final InstantSource clock;
  private final IdleWatchdog idleWatchdog;
  private final StringBuilder content = new StringBuilder();
  private final List<ToolCall> toolCalls = new CopyOnWriteArrayList<>();
  private final CountDownLatch done = new CountDownLatch(1);
  private final AtomicReference<FinishReason> finishReason =
      new AtomicReference<>(FinishReason.STOP);
  private final AtomicReference<Usage> usage = new AtomicReference<>(Usage.of(0, 0));
  private final AtomicReference<Map<String, String>> metadata = new AtomicReference<>(Map.of());
  private final AtomicReference<List<Citation>> citations = new AtomicReference<>(List.of());
  private final AtomicReference<Throwable> error = new AtomicReference<>();

  TurnSubscriber(
      SessionState state,
      EventEmitter emitter,
      InstantSource clock,
      ScheduledExecutorService scheduler,
      Duration idleTimeout) {
    this.state = state;
    this.emitter = emitter;
    this.clock = clock;
    this.idleWatchdog =
        new IdleWatchdog(scheduler, idleTimeout, () -> done.getCount() == 0L, this::fail);
  }

  @Override
  public void onSubscribe(Flow.Subscription subscription) {
    idleWatchdog.arm();
    subscription.request(Long.MAX_VALUE);
  }

  @Override
  public void onNext(ModelChunk chunk) {
    idleWatchdog.arm();
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

  private void handleTextDelta(String text) {
    content.append(text);
    emitter.emit(
        state,
        new QueryEvent.AssistantText(
            state.sessionId(), state.currentTurnIndex(), clock.instant(), text));
  }

  private void handleThinkingDelta(String text) {
    emitter.emit(
        state,
        new QueryEvent.AssistantThinking(
            state.sessionId(), state.currentTurnIndex(), clock.instant(), text, ""));
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
              state.sessionId(), state.currentTurnIndex(), clock.instant(), turnCitations));
    }
  }

  @Override
  public void onError(Throwable t) {
    idleWatchdog.cancel();
    error.set(t);
    finishReason.set(FinishReason.ERROR);
    done.countDown();
  }

  @Override
  public void onComplete() {
    idleWatchdog.cancel();
    done.countDown();
  }

  /**
   * Block until the producer's terminal signal fires, the session's {@link CancellationToken} is
   * cancelled, or the calling thread is interrupted, then return what the stream produced. Without
   * the cancellation hook a provider stream that never delivers {@code onComplete} / {@code
   * onError} (silent socket, hung proxy) would pin this thread indefinitely — defeating {@link
   * com.standardapplied.helios.session.SessionLimits#maxWallClock()} which is only re-checked at
   * turn boundaries.
   *
   * <p>The cancellation callback is removed in {@code finally} so a long-lived session token does
   * not accumulate one stale registration per turn (see the {@code CancellationToken.onCancel}
   * cleanup contract).
   */
  StreamedTurn awaitDone(CancellationToken cancellation) {
    var registration =
        cancellation.onCancel(
            () ->
                fail(
                    new CancellationException("session cancelled while waiting for model stream")));
    try {
      done.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      error.compareAndSet(null, e);
      finishReason.set(FinishReason.ERROR);
    } finally {
      registration.remove();
      idleWatchdog.cancel();
    }
    var failure = error.get();
    return new StreamedTurn(
        content.toString(),
        List.copyOf(toolCalls),
        citations.get(),
        finishReason.get(),
        usage.get(),
        metadata.get(),
        failure);
  }

  /** End the stream with {@code cause} unless it already ended with an error. */
  private void fail(Throwable cause) {
    error.compareAndSet(null, cause);
    finishReason.set(FinishReason.ERROR);
    done.countDown();
  }

  private static FinishReason parseFinishReason(String stopReason) {
    try {
      return FinishReason.valueOf(stopReason);
    } catch (IllegalArgumentException ignored) {
      return FinishReason.STOP;
    }
  }
}
