/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static com.standardapplied.helios.session.EndingSessions.binding;
import static com.standardapplied.helios.session.EndingSessions.slowGrace;
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
import com.standardapplied.helios.session.hooks.PreStopHook;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * A session is running from the {@code send} that starts a run until that run's future has
 * completed and the session has left it, on every way a run ends, and while a callback of that
 * future starts and waits for the next run.
 */
final class SessionRunsSettlingTest {

  @Test
  void aRunTheSessionLeftIsRunningUntilItsFutureHasCompleted() {
    var tearingDown = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var provider = new RecordingProvider(new CopyOnWriteArrayList<>());
    provider.end =
        () -> {
          tearingDown.countDown();
          Uninterruptible.await(release);
        };
    var session = new AtomicReference<AgentSession>();
    var closing =
        binding(
            "close",
            (args, ctx) -> {
              session.get().close();
              return ToolResult.success("closed");
            });
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(new ToolCall("c1", "close", Map.of()))
            .withTextTurn("never")
            .build();
    var open =
        AgentSession.open(
            options(model)
                .withExecutionProvider(provider)
                .withTools(List.of(closing))
                .withLimits(slowGrace())
                .build());
    session.set(open);
    var run = open.send("go");

    Await.latch("the run's thread to tear the session down", tearingDown);
    assertFalse(run.isDone());
    assertTrue(open.isRunning());

    release.countDown();
    assertInstanceOf(ResultMessage.Cancelled.class, Await.value("the run", run));
    Await.until("the session to settle the run", () -> !open.isRunning());
  }

  @Test
  void aRunThatSucceedsIsNoLongerRunningOnceSettled() {
    var model = ScriptedModel.newBuilder().withTextTurn("done").build();
    try (var session = AgentSession.open(options(model).build())) {
      var run = session.send("go");

      assertInstanceOf(ResultMessage.Success.class, Await.value("the run", run));
      Await.until("the session to settle the run", () -> !session.isRunning());
    }
  }

  @Test
  void aRunARuntimeExceptionCrashesIsNoLongerRunningOnceSettled() {
    var crash = new IllegalStateException("clock failure");
    InstantSource clock =
        () -> {
          if (Thread.currentThread().getName().startsWith("helios-agent-run-")) {
            throw crash;
          }
          return Instant.EPOCH;
        };
    var model = ScriptedModel.newBuilder().withTextTurn("never").build();
    try (var session = AgentSession.open(options(model).withClock(clock).build())) {
      var run = session.send("go");

      assertSame(crash, Await.failure("the run", run));
      Await.until("the session to settle the run", () -> !session.isRunning());
    }
  }

  @Test
  void aRunAHostErrorFailsLeavesAFailedSessionThatIsNotRunningOnceSettled() {
    var sentinel = new AssertionError("hook failure");
    PreStopHook failing =
        (response, ctx) -> {
          throw sentinel;
        };
    var model = ScriptedModel.newBuilder().withTextTurn("done").build();
    try (var session = AgentSession.open(options(model).withHook(failing).build())) {
      var run = session.send("go");

      assertSame(sentinel, Await.failure("the run", run));
      Await.until("the session to settle the run", () -> !session.isRunning());
      var refused = assertThrows(IllegalStateException.class, () -> session.send("again"));
      assertEquals("session failed: java.lang.AssertionError", refused.getMessage());
    }
  }

  @Test
  void aCallbackOfARunThatStartsAndWaitsForTheNextKeepsTheSessionRunningUntilItReturns() {
    var held = new CountDownLatch(1);
    var attached = new CountDownLatch(1);
    var answered = new CountDownLatch(1);
    var returning = new CountDownLatch(1);
    var runThreads = new CopyOnWriteArrayList<Thread>();
    PreStopHook holdingTheFirst =
        (response, ctx) -> {
          runThreads.add(Thread.currentThread());
          if (runThreads.size() == 1) {
            held.countDown();
            Uninterruptible.await(attached);
          }
          return HookOutcome.cont();
        };
    var model = ScriptedModel.newBuilder().withTextTurn("one").withTextTurn("two").build();
    var options = options(model).withHook(holdingTheFirst).withLimits(slowGrace());
    try (var session = AgentSession.open(options.build())) {
      var first = session.send("go");
      Await.latch("run 1 to reach its stop", held);
      var chained =
          first.thenApply(
              result -> {
                var next = session.send("again").join();
                answered.countDown();
                Uninterruptible.await(returning);
                return next;
              });
      attached.countDown();

      Await.latch("run 2 to answer the callback", answered);
      Await.termination("run 2's thread", runThreads.get(1));
      assertTrue(session.isRunning());

      returning.countDown();
      assertInstanceOf(ResultMessage.Success.class, Await.value("the callback", chained));
      Await.until("the session to settle both runs", () -> !session.isRunning());
      assertEquals(2, model.calls().size());
    }
  }

  @Test
  void aHostThatWaitsForTheSessionToStopRunningClosesItWithTheRunsFutureComplete() {
    var model = ScriptedModel.newBuilder().withTextTurn("done").build();
    var session = AgentSession.open(options(model).build());
    var run = session.send("go");

    Await.until("the session to stop running", () -> !session.isRunning());
    session.close();

    assertTrue(run.isDone());
  }

  private static SessionOptions.Builder options(ScriptedModel model) {
    return SessionOptions.newBuilder().withModel(model).withSessionId("sess-runs-settling");
  }
}
