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
import java.util.concurrent.TimeUnit;

/**
 * Translates a model's {@link ModelChunk} stream into per-turn {@link QueryEvent}s and accumulates
 * what the stream produced into a {@link StreamedTurn}.
 *
 * <p>Created once per model turn by {@link TurnRunner}. It owns the turn's {@link
 * Flow.Subscription}: it requests every chunk on subscribe, and cancels the stream when the {@link
 * IdleWatchdog} finds it stalled, when the session's {@link CancellationToken} is cancelled, when
 * the waiting thread is interrupted, or when opening or subscribing to the stream threw. The idle
 * clock starts at the first chunk, so a request still awaiting its headers is bounded by the
 * provider's response timeout, the session's wall clock and its cancellation, never by the idle
 * clock; every chunk re-arms it, a {@link ModelChunk.KeepAlive} included, which adds and emits
 * nothing. The runner blocks in {@link #awaitDone(CancellationToken)} until the stream ends one of
 * those ways or with {@code onComplete} / {@code onError}.
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
 *
 * <h2>Cancelling the subscription</h2>
 *
 * The subscription is the provider's code, so it is cancelled through {@link StreamCancel}, within
 * {@code cancelGrace} ({@link com.standardapplied.helios.session.SessionLimits#teardownGrace()}): a
 * cancel that blocks never strands the runner. The turn's outcome is published once the cancel
 * returned or its grace passed, whichever thread ended the turn.
 *
 * <h2>Host errors</h2>
 *
 * Every {@link Error} that reaches the turn, through {@code onError}, a chunk's emission, the
 * subscription's request or its cancel, on whichever thread and whenever it arrives, goes to the
 * session's {@link SessionEnding}, which ends the session.
 */
final class TurnSubscriber implements Flow.Subscriber<ModelChunk> {

  private static final long CANCELLATION_CHECK_MILLIS = 10;
  private static final String CANCELLED = "session cancelled while waiting for model stream";

  private final SessionState state;
  private final EventEmitter emitter;
  private final InstantSource clock;
  private final IdleWatchdog idleWatchdog;
  private final StreamCancel streamCancel;
  private final SessionEnding ending;
  private final CountDownLatch done = new CountDownLatch(1);
  private final Object lock = new Object();
  private final StringBuilder content = new StringBuilder();
  private final List<ToolCall> toolCalls = new ArrayList<>();
  private ModelChunk.MessageStop stop;
  private Flow.Subscription subscription;
  private boolean emitting;
  private boolean ended;
  private StreamedTurn outcome;

  TurnSubscriber(
      SessionState state,
      EventEmitter emitter,
      InstantSource clock,
      ScheduledExecutorService scheduler,
      Duration idleTimeout,
      Duration cancelGrace) {
    this.state = state;
    this.emitter = emitter;
    this.clock = clock;
    this.idleWatchdog =
        new IdleWatchdog(scheduler, idleTimeout, this::released, stalled -> end(stalled, true));
    this.ending = state.ending();
    this.streamCancel = new StreamCancel(state.sessionId(), cancelGrace, ending);
  }

  /**
   * Accept the first subscription and request every chunk, or cancel a subscription that arrives
   * second or after the turn ended. What the request throws ends the turn, on whichever thread
   * delivers the subscription, and is rethrown.
   *
   * @throws RuntimeException what requesting from the accepted subscription threw
   * @throws Error what requesting from the accepted subscription threw
   */
  @Override
  public void onSubscribe(Flow.Subscription subscription) {
    boolean accepted;
    synchronized (lock) {
      accepted = this.subscription == null && !ended;
      if (accepted) {
        this.subscription = subscription;
      }
    }
    if (accepted) {
      try {
        subscription.request(Long.MAX_VALUE);
      } catch (RuntimeException | Error failure) {
        end(failure, true);
        throw failure;
      }
      return;
    }
    streamCancel.cancel(subscription);
  }

  /**
   * Accumulate {@code chunk} and emit its event. An {@link Error} the emission throws, a stream
   * event hook's, ends the turn before the runner is released, and is rethrown.
   *
   * @throws Error what emitting the chunk's event threw
   */
  @Override
  public void onNext(ModelChunk chunk) {
    boolean subscribed;
    synchronized (lock) {
      if (ended) {
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
    } catch (Error error) {
      end(error, true);
      throw error;
    } finally {
      boolean release;
      synchronized (lock) {
        emitting = false;
        release = outcome != null;
      }
      if (release) {
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
   * <p>An interrupt ends the stream as cancellation does: this keeps waiting until the turn's
   * outcome is published, whichever thread ended it and is cancelling the subscription within its
   * grace, and only then restores the interrupt status.
   *
   * <p>The token is also read as this waits: it runs its callbacks one after another, so a callback
   * registered before this one that blocks would otherwise keep the stream open and the session
   * from ever reaching its teardown. The cancellation callback is removed in {@code finally} so a
   * long-lived session token does not accumulate one stale registration per turn (see the {@code
   * CancellationToken.onCancel} cleanup contract).
   */
  StreamedTurn awaitDone(CancellationToken cancellation) {
    var registration = cancellation.onCancel(() -> end(new CancellationException(CANCELLED), true));
    var interrupted = false;
    try {
      while (!released()) {
        try {
          if (!done.await(CANCELLATION_CHECK_MILLIS, TimeUnit.MILLISECONDS)
              && cancellation.isCancelled()) {
            end(new CancellationException(CANCELLED), true);
          }
        } catch (InterruptedException e) {
          interrupted = true;
          end(e, true);
        }
      }
    } finally {
      registration.remove();
      idleWatchdog.cancel();
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
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
   * The one terminal transition: unless the stream already ended, cancel the subscription when
   * {@code cancelStream} and one is held, snapshot the stream with {@code error} (or none, for a
   * completion), and release the runner, or leave its release to the chunk whose event is being
   * emitted. An {@link Error} goes to the session's {@link SessionEnding} first, whether or not the
   * stream already ended.
   */
  void end(Throwable error, boolean cancelStream) {
    if (error instanceof Error fatal) {
      ending.fail(fatal);
    }
    Flow.Subscription toCancel;
    synchronized (lock) {
      if (ended) {
        return;
      }
      ended = true;
      toCancel = cancelStream ? subscription : null;
    }
    idleWatchdog.cancel();
    if (toCancel != null) {
      streamCancel.cancel(toCancel);
    }
    boolean release;
    synchronized (lock) {
      outcome = snapshot(error);
      release = !emitting;
    }
    if (release) {
      done.countDown();
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
