/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.ModelChunk;
import ai.singlr.core.model.Response;
import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.core.tool.Tool;
import ai.singlr.session.hooks.DefaultHookContext;
import ai.singlr.session.hooks.HookRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the race-handling guards in {@link TurnSubscriber#armIdleTimer()} — the paths
 * the end-to-end {@link ai.singlr.session.AgentSessionStreamIdleTest} can't reach deterministically
 * because they depend on the relative ordering of producer / scheduler / runner threads.
 *
 * <p>Two guards are pinned here:
 *
 * <ul>
 *   <li>Re-arm after terminal — {@link Flow.Subscriber#onNext(Object)} fires <i>after</i> {@link
 *       Flow.Subscriber#onComplete()} (Reactive Streams forbids it, but real provider publishers
 *       have been seen to do it). The subscriber must drop the post-terminal chunk on the floor
 *       without scheduling a new idle task.
 *   <li>Idle timer is cancelled by {@code awaitDone}'s finally clause — released registrations
 *       leave no scheduled task pending against the session's deadline scheduler, even after the
 *       runner has moved on.
 * </ul>
 */
final class TurnSubscriberRaceTest {

  private static final String SID = "sess-race";
  private static final Clock CLOCK = Clock.fixed(java.time.Instant.EPOCH, ZoneOffset.UTC);
  private static final Duration IDLE = Duration.ofSeconds(60);

  @Test
  void onNextAfterOnCompleteDoesNotReArmIdleTimer() {
    var scheduler = countingScheduler();
    var subscriber = newSubscriber(scheduler);

    subscriber.onSubscribe(new NoopSubscription());
    var scheduledAtArm = scheduler.scheduleCount.get();
    assertEquals(1L, scheduledAtArm, "onSubscribe must arm the idle timer exactly once");

    subscriber.onComplete();
    // Post-terminal chunk — illegal per Reactive Streams but Helios must handle it gracefully.
    subscriber.onNext(new ModelChunk.TextDelta("ghost"));

    assertEquals(
        scheduledAtArm,
        scheduler.scheduleCount.get(),
        "armIdleTimer after onComplete must not schedule a new task");
  }

  @Test
  void onNextAfterOnErrorDoesNotReArmIdleTimer() {
    var scheduler = countingScheduler();
    var subscriber = newSubscriber(scheduler);

    subscriber.onSubscribe(new NoopSubscription());
    subscriber.onError(new java.io.IOException("provider went away"));
    var scheduledBeforeRace = scheduler.scheduleCount.get();

    subscriber.onNext(new ModelChunk.TextDelta("late"));

    assertEquals(
        scheduledBeforeRace,
        scheduler.scheduleCount.get(),
        "armIdleTimer after onError must not schedule a new task");
  }

  @Test
  void awaitDoneClearsIdleTimerAfterTerminal() {
    var scheduler = countingScheduler();
    var subscriber = newSubscriber(scheduler);

    subscriber.onSubscribe(new NoopSubscription());
    subscriber.onComplete();
    subscriber.awaitDone(new CancellationToken());

    // After awaitDone returns, no task should still be holding the scheduler — the prior task
    // was cancelled by onComplete, and awaitDone's finally clause cancels any successor.
    assertNull(subscriber.error(), "normal completion records no error");
    assertEquals(0, scheduler.outstandingCount(), "no scheduled task should outlive the turn");
  }

  // ── fixture ──────────────────────────────────────────────────────────────

  private static final Model STUB_MODEL =
      new Model() {
        @Override
        public Response<Void> chat(List<Message> messages, List<Tool> tools) {
          return Response.newBuilder().build();
        }

        @Override
        public String id() {
          return "stub";
        }

        @Override
        public String provider() {
          return "stub";
        }
      };

  private static TurnSubscriber newSubscriber(ScheduledExecutorService scheduler) {
    var state = new SessionState(SID, new CancellationToken(), CLOCK);
    var emitter =
        new EventEmitter(
            e -> {},
            HookRegistry.empty(),
            s ->
                new DefaultHookContext(
                    s.sessionId(), s.currentTurnIndex(), s.cancellation(), STUB_MODEL),
            CLOCK);
    return new TurnSubscriber(state, emitter, CLOCK, scheduler, IDLE);
  }

  private static CountingScheduler countingScheduler() {
    return new CountingScheduler();
  }

  /**
   * Decorates a real single-thread scheduler so tests can assert the number of {@code schedule}
   * invocations. Real scheduling backs the idle timer in production; we just count, not stub.
   */
  private static final class CountingScheduler extends ScheduledThreadPoolExecutor {

    final AtomicLong scheduleCount = new AtomicLong();
    final List<ScheduledFuture<?>> futures = new ArrayList<>();

    CountingScheduler() {
      super(
          1,
          r -> {
            var t = new Thread(r, "test-idle-scheduler");
            t.setDaemon(true);
            return t;
          });
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      scheduleCount.incrementAndGet();
      var f = super.schedule(command, delay, unit);
      futures.add(f);
      return f;
    }

    int outstandingCount() {
      var n = 0;
      for (var f : futures) {
        if (!f.isDone() && !f.isCancelled()) {
          n++;
        }
      }
      return n;
    }
  }

  private static final class NoopSubscription implements Flow.Subscription {
    @Override
    public void request(long n) {}

    @Override
    public void cancel() {}
  }
}
