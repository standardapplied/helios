/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static com.standardapplied.helios.session.EndingSessions.binding;
import static com.standardapplied.helios.session.EndingSessions.slowGrace;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.Uninterruptible;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.test.EventCollector;
import com.standardapplied.helios.testing.ModelStreams;
import com.standardapplied.helios.testing.ScriptedModel;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * A close cancels the run in progress before it returns, even from inside that run, and returns
 * only once the run's thread has settled the run's future.
 */
final class SessionCloseTest {

  @Test
  void aCloseFromInsideAToolCancelsItsRunBeforeItReturns() {
    var session = new AtomicReference<AgentSession>();
    var reasonOnReturn = new CompletableFuture<Optional<String>>();
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(new ToolCall("c1", "close", Map.of()))
            .withTextTurn("never")
            .build();
    var closing =
        binding(
            "close",
            (args, ctx) -> {
              var run = Run.stateOfThisThread();
              // Holding the run's state keeps the session token's own link to the run from
              // cancelling it on another thread, so only the close itself can have cancelled it.
              synchronized (run) {
                session.get().close();
                reasonOnReturn.complete(run.cancellation().reason());
              }
              return ToolResult.success("closed");
            });
    var open = AgentSession.open(options(model).withTools(List.of(closing)).build());
    session.set(open);

    var run = open.send("go");

    assertEquals(
        Optional.of("session closed"), Await.value("close to return in the tool", reasonOnReturn));
    assertInstanceOf(ResultMessage.Cancelled.class, Await.value("the run", run));
  }

  @Test
  void aCloseReturnsOnlyOnceTheRunsThreadHasSettledTheRunsFuture() {
    var entered = new CountDownLatch(1);
    var settling = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var model =
        ScriptedModel.newBuilder().withStreamTurn(ModelStreams.stalled(entered::countDown)).build();
    var session = AgentSession.open(options(model).withLimits(slowGrace()).build());
    var events = new EventCollector().read(session.events());
    var run = session.send("go");
    Await.latch("the model stream to be read", entered);
    run.whenComplete(
        (result, failure) -> {
          settling.countDown();
          Uninterruptible.await(release);
        });

    var closing = Thread.ofVirtual().start(session::close);
    Await.latch("the run's thread to settle the run's future", settling);
    events.awaitDone();
    Await.until(
        "close to wait for the run's thread or return",
        () ->
            closing.getState() == Thread.State.TIMED_WAITING
                || closing.getState() == Thread.State.TERMINATED);

    assertEquals(Thread.State.TIMED_WAITING, closing.getState(), "close returned before settling");
    release.countDown();
    Await.termination("the close", closing);
  }

  private static SessionOptions.Builder options(ScriptedModel model) {
    return SessionOptions.newBuilder().withModel(model).withSessionId("sess-close");
  }
}
