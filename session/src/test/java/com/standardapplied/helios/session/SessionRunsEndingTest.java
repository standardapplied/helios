/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static com.standardapplied.helios.session.EndingSessions.NOT_REACHED;
import static com.standardapplied.helios.session.EndingSessions.SHORT_GRACE;
import static com.standardapplied.helios.session.EndingSessions.binding;
import static com.standardapplied.helios.session.EndingSessions.holdingItsEnd;
import static com.standardapplied.helios.session.EndingSessions.stream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.Uninterruptible;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.OnStreamEventHook;
import com.standardapplied.helios.session.hooks.PreModelTurnHook;
import com.standardapplied.helios.session.hooks.PreStopHook;
import com.standardapplied.helios.session.test.EventCollector;
import com.standardapplied.helios.session.test.LogCapture;
import com.standardapplied.helios.testing.ModelStreams;
import com.standardapplied.helios.testing.ScriptedModel;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;

/**
 * How a session's close and a host {@link Error} end its runs: the run in progress, the next run
 * waiting behind it, the execution provider and the event stream, and the callbacks of their
 * futures.
 */
final class SessionRunsEndingTest {

  @Test
  void aNextRunTheSessionsCloseKeepsFromStartingIsCancelled() {
    var model = ScriptedModel.newBuilder().withTextTurn("one").withTextTurn("never").build();
    var ending = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var session = AgentSession.open(holdingItsEnd(options(model), ending, release).build());
    var first = session.send("go");
    Await.latch("the run to decide its end", ending);
    var next = session.send("next");

    var closing = Thread.ofVirtual().start(session::close);
    Await.until(
        "the close to wait for the run", () -> closing.getState() == Thread.State.TIMED_WAITING);
    release.countDown();
    Await.termination("the close", closing);

    assertInstanceOf(ResultMessage.Success.class, Await.value("run 1", first));
    assertInstanceOf(CancellationException.class, Await.failure("the next run", next));
    assertEquals(1, model.calls().size());
  }

  @Test
  void aCallbackOfANextRunTheCloseCancelsDoesNotHoldUpTheTeardown() {
    var provider = new RecordingProvider(new CopyOnWriteArrayList<>());
    var model = ScriptedModel.newBuilder().withTextTurn("one").withTextTurn("never").build();
    var ending = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var session =
        AgentSession.open(
            holdingItsEnd(options(model), ending, release).withExecutionProvider(provider).build());
    var stream = session.events();
    session.send("go");
    Await.latch("the run to decide its end", ending);
    var next = session.send("next");
    var read = new CountDownLatch(1);
    next.whenComplete(
        (result, failure) -> {
          stream.forEach(event -> {});
          read.countDown();
        });

    var closing = Thread.ofVirtual().start(session::close);
    Await.until(
        "the close to wait for the run", () -> closing.getState() == Thread.State.TIMED_WAITING);
    release.countDown();
    Await.termination("the close", closing);

    assertInstanceOf(CancellationException.class, Await.failure("the next run", next));
    assertEquals(1, provider.ends.get());
    Await.latch("the callback to read the stream to its end", read);
  }

  @Test
  void aCallbackOfARunAnErrorEndsCanWaitForTheNextRunThatErrorFails() {
    failARunWithANextOneQueued(false);
  }

  @Test
  void aCallbackOfTheNextRunAnErrorFailsCanWaitForTheRunBeforeIt() {
    failARunWithANextOneQueued(true);
  }

  /**
   * Fail run 1 with an {@link Error} while a next run waits behind it and a callback of one of the
   * two futures, the next run's when {@code nextWaits}, waits for the other: both fail with that
   * {@code Error}, the callback returns, and the next run never called the model.
   */
  private static void failARunWithANextOneQueued(boolean nextWaits) {
    var model = ScriptedModel.newBuilder().withTextTurn("one").withTextTurn("never").build();
    var sentinel = new AssertionError("callback failure");
    var ending = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    PreStopHook failingAtItsEnd =
        (response, ctx) -> {
          ctx.cancellation()
              .onCancel(
                  () -> {
                    ending.countDown();
                    Uninterruptible.await(release);
                    throw sentinel;
                  });
          return HookOutcome.cont();
        };

    try (var session = AgentSession.open(options(model).withHook(failingAtItsEnd).build())) {
      var first = session.send("go");
      Await.latch("the run to decide its end", ending);
      var next = session.send("next");
      var waiting = nextWaits ? next : first;
      var awaited = nextWaits ? first : next;
      var chained =
          waiting.whenComplete((result, failure) -> awaited.handle((value, error) -> error).join());
      release.countDown();

      assertSame(sentinel, Await.failure("run 1", first));
      assertSame(sentinel, Await.failure("the next run", next));
      assertSame(sentinel, Await.failure("the callback", chained));
      assertEquals(1, model.calls().size());
    }
  }

  // ── 7. close ─────────────────────────────────────────────────────────────

  @Test
  void aCloseEndsTheRunBeforeItReturnsEndsTheProviderOnceAndEndsTheStream() {
    var provider = new RecordingProvider(new CopyOnWriteArrayList<>());
    var entered = new CountDownLatch(1);
    var model =
        ScriptedModel.newBuilder().withStreamTurn(ModelStreams.stalled(entered::countDown)).build();
    var events = new EventCollector();
    var session = AgentSession.open(options(model).withExecutionProvider(provider).build());
    events.read(session.events());
    var run = session.send("go");
    Await.latch("the model stream to be read", entered);

    session.close();

    assertTrue(run.isDone(), "the run ended before close returned");
    var cancelled = assertInstanceOf(ResultMessage.Cancelled.class, run.join());
    assertEquals("session closed", cancelled.reason());
    assertEquals(1, provider.ends.get());
    var refused = assertThrows(IllegalStateException.class, () -> session.send("again"));
    assertEquals("session is closed", refused.getMessage());
    events.awaitDone();
  }

  @Test
  void aCallbackOfTheRunsFutureThatWaitsForTheCloseNeitherRunsOnItNorHoldsItPastItsGrace() {
    var entered = new CountDownLatch(1);
    var closeReturned = new CountDownLatch(1);
    var callbackThreads = new CopyOnWriteArrayList<Thread>();
    var model =
        ScriptedModel.newBuilder().withStreamTurn(ModelStreams.stalled(entered::countDown)).build();
    var session =
        AgentSession.open(
            options(model)
                .withLimits(SessionLimits.newBuilder().withStopGrace(SHORT_GRACE).build())
                .build());
    var run = session.send("go");
    Await.latch("the model stream to be read", entered);
    for (var i = 0; i < 2; i++) {
      run.whenComplete(
          (result, failure) -> {
            callbackThreads.add(Thread.currentThread());
            Uninterruptible.await(closeReturned);
          });
    }

    var closing = new AtomicReference<Thread>();
    Thread.ofVirtual()
        .start(
            () -> {
              closing.set(Thread.currentThread());
              session.close();
              closeReturned.countDown();
            });

    Await.latch("close to return", closeReturned);
    assertTrue(run.isDone(), "the run ended before close returned");
    Await.until("both callbacks to run", () -> callbackThreads.size() == 2);
    assertFalse(callbackThreads.contains(closing.get()), "a callback ran on the closing thread");
  }

  @Test
  void aCloseFromACallbackOfAnInterruptReturnsAndTheRunsThreadEndsTheSession() {
    var provider = new RecordingProvider(new CopyOnWriteArrayList<>());
    var session = new AtomicReference<AgentSession>();
    var entered = new CountDownLatch(1);
    var closed = new CountDownLatch(1);
    var model =
        ScriptedModel.newBuilder().withStreamTurn(ModelStreams.stalled(entered::countDown)).build();
    PreModelTurnHook closingOnCancel =
        (history, ctx) -> {
          ctx.cancellation()
              .onCancel(
                  () -> {
                    session.get().close();
                    closed.countDown();
                  });
          return HookOutcome.cont();
        };
    var open =
        AgentSession.open(
            options(model)
                .withExecutionProvider(provider)
                .withHook(closingOnCancel)
                .withLimits(SessionLimits.newBuilder().withStopGrace(NOT_REACHED).build())
                .build());
    session.set(open);
    var run = open.send("go");
    Await.latch("the model stream to be read", entered);

    open.interrupt();

    Await.latch("close to return inside the callback", closed);
    var cancelled = assertInstanceOf(ResultMessage.Cancelled.class, Await.value("the run", run));
    assertEquals("interrupted", cancelled.reason());
    Await.until("the run's thread to end the provider", () -> provider.ends.get() == 1);
  }

  @Test
  void aCloseFromInsideAToolReturnsAndTheRunsThreadEndsTheSession() {
    var provider = new RecordingProvider(new CopyOnWriteArrayList<>());
    var session = new AtomicReference<AgentSession>();
    var closed = new CountDownLatch(1);
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(new ToolCall("c1", "close", Map.of()))
            .withTextTurn("never")
            .build();
    var closing =
        binding(
            "close",
            (args, ctx) -> {
              session.get().close();
              closed.countDown();
              return ToolResult.success("closed");
            });
    var open =
        AgentSession.open(
            options(model).withExecutionProvider(provider).withTools(List.of(closing)).build());
    session.set(open);

    var run = open.send("go");

    Await.latch("close to return inside the tool", closed);
    assertInstanceOf(ResultMessage.Cancelled.class, Await.value("the run", run));
    Await.until("the run's thread to end the provider", () -> provider.ends.get() == 1);
  }

  // ── 8. host Error ────────────────────────────────────────────────────────

  @Test
  void anErrorAToolThrowsFailsTheRunEndsTheProviderAndRefusesLaterMessages() {
    var provider = new RecordingProvider(new CopyOnWriteArrayList<>());
    var sentinel = new AssertionError("tool failure");
    var model =
        ScriptedModel.newBuilder().withToolCallsTurn(new ToolCall("c1", "boom", Map.of())).build();
    var boom =
        binding(
            "boom",
            (args, ctx) -> {
              throw sentinel;
            });

    try (var session =
        AgentSession.open(
            options(model).withExecutionProvider(provider).withTools(List.of(boom)).build())) {
      assertSame(sentinel, Await.failure("the run to fail", session.send("go")));
      Await.until("the session to end its provider", () -> provider.ends.get() == 1);

      var refused = assertThrows(IllegalStateException.class, () -> session.send("again"));
      assertEquals("session failed: java.lang.AssertionError", refused.getMessage());
    }
  }

  @Test
  void aCallbackOfARunAnErrorFailsDoesNotHoldUpTheTeardown() {
    var provider = new RecordingProvider(new CopyOnWriteArrayList<>());
    var sentinel = new AssertionError("tool failure");
    var release = new CountDownLatch(1);
    var model =
        ScriptedModel.newBuilder().withToolCallsTurn(new ToolCall("c1", "boom", Map.of())).build();
    var boom =
        binding(
            "boom",
            (args, ctx) -> {
              Uninterruptible.await(release);
              throw sentinel;
            });

    try (var session =
        AgentSession.open(
            options(model).withExecutionProvider(provider).withTools(List.of(boom)).build())) {
      var stream = session.events();
      var run = session.send("go");
      var read = new CountDownLatch(1);
      run.whenComplete(
          (result, failure) -> {
            stream.forEach(event -> {});
            read.countDown();
          });
      release.countDown();

      Await.latch("the callback to read the stream to its end", read);
      assertSame(sentinel, Await.failure("the run", run));
      assertEquals(1, provider.ends.get());
    }
  }

  @Test
  void anErrorThatReachesAnIdleSessionEndsItsProviderAndRefusesLaterMessages() {
    var provider = new RecordingProvider(new CopyOnWriteArrayList<>());
    var sentinel = new AssertionError("late callback failure");
    var release = new CountDownLatch(1);
    var logged = new CopyOnWriteArrayList<LogRecord>();
    var model = ScriptedModel.newBuilder().withTextTurn("one").build();
    PreStopHook failingLate =
        (response, ctx) -> {
          ctx.cancellation()
              .onCancel(
                  () -> {
                    Uninterruptible.await(release);
                    throw sentinel;
                  });
          return HookOutcome.cont();
        };
    var options =
        options(model)
            .withExecutionProvider(provider)
            .withHook(failingLate)
            .withLimits(SessionLimits.newBuilder().withStopGrace(SHORT_GRACE).build());

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged);
        var session = AgentSession.open(options.build())) {
      assertInstanceOf(ResultMessage.Success.class, Await.value("the run", session.send("go")));
      Await.until("the run to be left", () -> !session.isRunning());

      release.countDown();

      Await.until("the session to end its provider", () -> provider.ends.get() == 1);
      var refused = assertThrows(IllegalStateException.class, () -> session.send("again"));
      assertEquals("session failed: java.lang.AssertionError", refused.getMessage());
      Await.until(
          "the Error no run carries to be logged",
          () -> logged.stream().anyMatch(record -> record.getThrown() == sentinel));
    }
  }

  @Test
  void anErrorRecordedWhileTheRunsThreadWaitsToLeaveTheRunFailsItAndEndsTheSession() {
    var provider = new RecordingProvider(new CopyOnWriteArrayList<>());
    var sentinel = new AssertionError("late callback failure");
    var atRunEnded = new CountDownLatch(1);
    var hold = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var runThread = new AtomicReference<Thread>();
    var callbackThread = new AtomicReference<Thread>();
    var model = ScriptedModel.newBuilder().withTextTurn("one").build();
    PreStopHook failingLate =
        (response, ctx) -> {
          ctx.cancellation()
              .onCancel(
                  () -> {
                    callbackThread.set(Thread.currentThread());
                    Uninterruptible.await(release);
                    throw sentinel;
                  });
          return HookOutcome.cont();
        };
    var options =
        options(model)
            .withExecutionProvider(provider)
            .withHook(failingLate)
            .withHook(
                (OnStreamEventHook)
                    (event, ctx) -> {
                      if (event instanceof QueryEvent.RunEnded) {
                        runThread.set(Thread.currentThread());
                        atRunEnded.countDown();
                        Uninterruptible.await(hold);
                      }
                    })
            .withLimits(SessionLimits.newBuilder().withStopGrace(SHORT_GRACE).build());

    try (var session = AgentSession.open(options.build())) {
      var run = session.send("go");
      Await.latch("the run's thread to emit RunEnded", atRunEnded);
      synchronized (((AgentSessionImpl) session).lifecycle.runs().lock) {
        hold.countDown();
        Await.until(
            "the run's thread to wait to leave the run",
            () -> runThread.get().getState() == Thread.State.BLOCKED);
        release.countDown();
        Await.until(
            "the Error to wait to fail the session",
            () ->
                callbackThread.get() != null
                    && callbackThread.get().getState() == Thread.State.BLOCKED);
      }

      assertSame(sentinel, Await.failure("the run", run));
      Await.until("the session to end its provider", () -> provider.ends.get() == 1);
    }
  }

  private static SessionOptions.Builder options(ScriptedModel model) {
    return SessionOptions.newBuilder().withModel(model).withSessionId("sess-runs-ending");
  }
}
