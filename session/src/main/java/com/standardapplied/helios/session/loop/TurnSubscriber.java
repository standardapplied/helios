/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.session.QueryEvent;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Translates a model's {@link ModelChunk} stream into per-turn {@link QueryEvent}s and accumulates
 * what the stream produced into a {@link StreamedTurn}.
 *
 * <p>Created once per model turn by {@link TurnRunner}. It owns the turn's {@link
 * Flow.Subscription}: it requests every chunk on subscribe, and cancels the stream when the {@link
 * IdleWatchdog} finds it stalled, when the session's {@link CancellationToken} is cancelled, or
 * when the waiting thread is interrupted. The runner blocks in {@link
 * #awaitDone(CancellationToken)} until the stream ends one of those ways or with {@code onComplete}
 * / {@code onError}.
 *
 * <h2>One terminal transition</h2>
 *
 * The first ending wins: it snapshots what the stream produced into an immutable {@link
 * StreamedTurn}, under the same lock that guards every accumulation, and releases the runner. A
 * signal that arrives after it, a second {@code onSubscribe}, or a chunk sent before {@code
 * onSubscribe} changes nothing and emits nothing; a late {@code onSubscribe} has its subscription
 * cancelled, and a chunk before {@code onSubscribe} ends the stream with an {@link
 * IllegalStateException}, as does an {@code onComplete} without the {@link ModelChunk.MessageStop}
 * that {@link com.standardapplied.helios.core.model.Model#chatStream(List, List,
 * CancellationToken)} ends a successful turn with. A chunk accepted before the ending emits its
 * event outside the lock, so a stalled event sink or hook never blocks a cancellation of the
 * stream; the runner is released once that event is out, so every event of the turn precedes the
 * runner's next one.
 */
final class TurnSubscriber implements Flow.Subscriber<ModelChunk> {

  private final SessionState state;
  private final EventEmitter emitter;
  private final InstantSource clock;
  private final IdleWatchdog idleWatchdog;
  private final CountDownLatch done = new CountDownLatch(1);
  private final Object lock = new Object();
  private final StringBuilder content = new StringBuilder();
  private final List<ToolCall> toolCalls = new ArrayList<>();
  private ModelChunk.MessageStop stop;
  private Flow.Subscription subscription;
  private boolean emitting;
  private StreamedTurn outcome;

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
        new IdleWatchdog(scheduler, idleTimeout, this::released, stalled -> end(stalled, true));
  }

  @Override
  public void onSubscribe(Flow.Subscription subscription) {
    boolean accepted;
    synchronized (lock) {
      accepted = this.subscription == null && outcome == null;
      if (accepted) {
        this.subscription = subscription;
      }
    }
    if (!accepted) {
      subscription.cancel();
      return;
    }
    idleWatchdog.arm();
    subscription.request(Long.MAX_VALUE);
  }

  @Override
  public void onNext(ModelChunk chunk) {
    boolean subscribed;
    synchronized (lock) {
      if (outcome != null) {
        return;
      }
      subscribed = subscription != null;
      if (subscribed) {
        accumulate(chunk);
        emitting = true;
      }
    }
    if (!subscribed) {
      end(new IllegalStateException("model stream sent a chunk before onSubscribe"), false);
      return;
    }
    try {
      idleWatchdog.arm();
      emit(chunk);
    } finally {
      boolean ended;
      synchronized (lock) {
        emitting = false;
        ended = outcome != null;
      }
      if (ended) {
        done.countDown();
      }
    }
  }

  private void emit(ModelChunk chunk) {
    switch (chunk) {
      case ModelChunk.TextDelta(String text) ->
          emitter.emit(
              state,
              new QueryEvent.AssistantText(
                  state.sessionId(), state.currentTurnIndex(), clock.instant(), text));
      case ModelChunk.ThinkingDelta(String text) ->
          emitter.emit(
              state,
              new QueryEvent.AssistantThinking(
                  state.sessionId(), state.currentTurnIndex(), clock.instant(), text, ""));
      case ModelChunk.MessageStop messageStop when !messageStop.citations().isEmpty() ->
          emitter.emit(
              state,
              new QueryEvent.AssistantCitations(
                  state.sessionId(),
                  state.currentTurnIndex(),
                  clock.instant(),
                  messageStop.citations()));
      default -> {}
    }
  }

  private void accumulate(ModelChunk chunk) {
    switch (chunk) {
      case ModelChunk.TextDelta(String text) -> content.append(text);
      case ModelChunk.ToolUseStop(ToolCall call) -> toolCalls.add(call);
      case ModelChunk.MessageStop messageStop -> stop = messageStop;
      default -> {}
    }
  }

  @Override
  public void onError(Throwable t) {
    end(t, false);
  }

  @Override
  public void onComplete() {
    end(null, false);
  }

  /**
   * Block until the stream ends, then return what it produced. The session's {@link
   * CancellationToken} and an interrupt of the calling thread each end the stream and cancel it;
   * without them a provider stream that never delivers {@code onComplete} / {@code onError} (silent
   * socket, hung proxy) would pin this thread indefinitely — defeating {@link
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
                end(
                    new CancellationException("session cancelled while waiting for model stream"),
                    true));
    try {
      done.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      end(e, true);
    } finally {
      registration.remove();
      idleWatchdog.cancel();
    }
    synchronized (lock) {
      return outcome;
    }
  }

  /** Whether the stream has ended and the runner has been released. */
  boolean released() {
    return done.getCount() == 0L;
  }

  /**
   * The one terminal transition: unless the stream already ended, snapshot it with {@code error}
   * (or none, for a completion), cancel the subscription when {@code cancelStream} and one is held,
   * and release the runner, or leave its release to the chunk whose event is being emitted.
   */
  private void end(Throwable error, boolean cancelStream) {
    Flow.Subscription toCancel;
    boolean release;
    synchronized (lock) {
      if (outcome != null) {
        return;
      }
      outcome = snapshot(error);
      toCancel = cancelStream ? subscription : null;
      release = !emitting;
    }
    try {
      idleWatchdog.cancel();
      if (toCancel != null) {
        toCancel.cancel();
      }
    } finally {
      if (release) {
        done.countDown();
      }
    }
  }

  private StreamedTurn snapshot(Throwable error) {
    var stopped = stop != null;
    var failure =
        stopped || error != null
            ? error
            : new IllegalStateException("model stream completed without its MessageStop");
    return new StreamedTurn(
        content.toString(),
        List.copyOf(toolCalls),
        stopped ? stop.citations() : List.of(),
        failure != null ? FinishReason.ERROR : stop.finishReason(),
        stopped ? stop.usage() : Usage.of(0, 0),
        stopped ? stop.metadata() : Map.of(),
        failure);
  }
}
