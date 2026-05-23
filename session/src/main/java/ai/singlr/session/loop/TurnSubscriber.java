/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.loop;

import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.ModelChunk;
import ai.singlr.core.model.Response.Usage;
import ai.singlr.core.model.ToolCall;
import ai.singlr.session.QueryEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
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
  private final StringBuilder content = new StringBuilder();
  private final List<ToolCall> toolCalls = new CopyOnWriteArrayList<>();
  private final CountDownLatch done = new CountDownLatch(1);
  private final AtomicReference<FinishReason> finishReason =
      new AtomicReference<>(FinishReason.STOP);
  private final AtomicReference<Usage> usage = new AtomicReference<>(Usage.of(0, 0));
  private final AtomicReference<Map<String, String>> metadata = new AtomicReference<>(Map.of());
  private final AtomicReference<Throwable> error = new AtomicReference<>();

  TurnSubscriber(SessionState state, EventEmitter emitter, Clock clock) {
    this.state = state;
    this.emitter = emitter;
    this.clock = clock;
  }

  @Override
  public void onSubscribe(Flow.Subscription subscription) {
    subscription.request(Long.MAX_VALUE);
  }

  @Override
  public void onNext(ModelChunk chunk) {
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
  }

  @Override
  public void onError(Throwable t) {
    error.set(t);
    finishReason.set(FinishReason.ERROR);
    done.countDown();
  }

  @Override
  public void onComplete() {
    done.countDown();
  }

  void awaitDone() {
    try {
      done.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      error.compareAndSet(null, e);
      finishReason.set(FinishReason.ERROR);
    }
  }

  TurnOutcome toOutcome() {
    var err = error.get();
    var assistantContent =
        err != null
            ? (err.getMessage() == null ? err.getClass().getSimpleName() : err.getMessage())
            : content.toString();
    return new TurnOutcome(finishReason.get(), assistantContent, usage.get(), metadata.get());
  }

  List<ToolCall> toolCalls() {
    return new ArrayList<>(toolCalls);
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
