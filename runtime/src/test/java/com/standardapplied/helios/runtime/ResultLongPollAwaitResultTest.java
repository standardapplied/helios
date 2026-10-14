/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SerializedError;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the package-private {@link ResultLongPoll#awaitResult(CompletableFuture,
 * CompletableFuture, long, String)} helper. The integration test covers the happy and timeout paths
 * through HTTP; this fixture covers the exception paths (interrupted, execution-exception) that are
 * awkward to reach via a black-box HTTP call.
 */
final class ResultLongPollAwaitResultTest {

  private static final CompletableFuture<Void> LIVE = new CompletableFuture<>();

  private static ResultMessage stubSuccess() {
    return new ResultMessage.Success(
        "sess-1", "the result", Usage.of(1, 1), CostEstimate.zero(), Duration.ZERO, List.of());
  }

  @Test
  void successFutureReturns200WithTypedBody() {
    var future = CompletableFuture.completedFuture(stubSuccess());
    var outcome = ResultLongPoll.awaitResult(future, LIVE, 5L, "sess-1");
    assertEquals(io.helidon.http.Status.OK_200, outcome.status());
    @SuppressWarnings("unchecked")
    var body = (Map<String, Object>) outcome.body();
    assertEquals("Success", body.get("type"));
    assertEquals(stubSuccess(), body.get("result"));
    assertEquals(List.of("type", "result"), List.copyOf(body.keySet()));
  }

  @Test
  void unresolvedFutureWithinShortTimeoutReturns204() {
    var future = new CompletableFuture<ResultMessage>();
    var outcome = ResultLongPoll.awaitResult(future, LIVE, 0L, "sess-1");
    assertEquals(io.helidon.http.Status.NO_CONTENT_204, outcome.status());
    assertNull(outcome.body(), "204 must carry a null body sentinel");
  }

  @Test
  void interruptedWhileWaitingReturns503AndPreservesInterruptFlag() {
    var future = new CompletableFuture<ResultMessage>();
    Thread.currentThread().interrupt();
    try {
      var outcome = ResultLongPoll.awaitResult(future, LIVE, 60L, "sess-1");
      assertEquals(io.helidon.http.Status.SERVICE_UNAVAILABLE_503, outcome.status());
      @SuppressWarnings("unchecked")
      var body = (Map<String, Object>) outcome.body();
      assertTrue(body.get("error").toString().contains("interrupted"));
    } finally {
      // Clear the flag for any subsequent tests sharing this thread.
      assertTrue(Thread.interrupted(), "interrupt flag must have been re-set by awaitResult");
    }
  }

  @Test
  void exceptionallyCompletedFutureReturns500WithoutTheCauseMessage() {
    var future = new CompletableFuture<ResultMessage>();
    future.completeExceptionally(new IllegalStateException("backend exploded: token=s3cr3t"));
    var outcome = ResultLongPoll.awaitResult(future, LIVE, 5L, "sess-1");
    assertEquals(io.helidon.http.Status.INTERNAL_SERVER_ERROR_500, outcome.status());
    assertEquals(Map.of("error", "session terminated abnormally"), outcome.body());
  }

  @Test
  void cancelledFutureReturns500() {
    var future = new CompletableFuture<ResultMessage>();
    future.cancel(true);
    var outcome = ResultLongPoll.awaitResult(future, LIVE, 5L, "sess-1");
    assertEquals(io.helidon.http.Status.INTERNAL_SERVER_ERROR_500, outcome.status());
    assertEquals(Map.of("error", "session terminated abnormally"), outcome.body());
  }

  @Test
  void anEndedIdentityIsAnswered401EvenWhenTheResultIsReady() {
    var outcome =
        ResultLongPoll.awaitResult(
            CompletableFuture.completedFuture(stubSuccess()), ended(), 5L, "sess-1");
    assertEquals(io.helidon.http.Status.UNAUTHORIZED_401, outcome.status());
    assertEquals(Map.of("error", "unauthenticated"), outcome.body());
  }

  @Test
  void anEndedIdentityIsAnswered401EvenWhenTheResultFailed() {
    var future = new CompletableFuture<ResultMessage>();
    future.completeExceptionally(new IllegalStateException("backend exploded"));
    var outcome = ResultLongPoll.awaitResult(future, ended(), 5L, "sess-1");
    assertEquals(io.helidon.http.Status.UNAUTHORIZED_401, outcome.status());
  }

  @Test
  void anIdentityEndingWhileWaitingEndsTheWaitWith401() throws Exception {
    var identity = new CompletableFuture<Void>();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var waiting =
          executor.submit(
              () ->
                  ResultLongPoll.awaitResult(
                      new CompletableFuture<>(),
                      identity,
                      ResultLongPoll.MAX_RESULT_TIMEOUT_SECONDS,
                      "sess-1"));
      Await.until(
          "the long poll to wait on the identity", () -> identity.getNumberOfDependents() > 0);
      identity.complete(null);
      var outcome = Await.value("the long poll to end", waiting);
      assertEquals(io.helidon.http.Status.UNAUTHORIZED_401, outcome.status());
    }
  }

  private static CompletableFuture<Void> ended() {
    return CompletableFuture.completedFuture(null);
  }

  @Test
  void errorDuringExecutionTerminalPreservesStackFrames() {
    // The library does NOT auto-redact stack frames. Frames are useful diagnostic info for the
    // deployer (and their support tooling); deployers that want them stripped before reaching
    // their downstream clients can call result.withoutStackTraces() at their HTTP layer.
    var carrying = SerializedError.of(new IllegalStateException("internal detail"));
    assertTrue(carrying.stackTrace().size() > 0, "test precondition");
    var terminal =
        new ResultMessage.ErrorDuringExecution(
            "sess-1", carrying, Usage.of(1, 1), CostEstimate.zero(), Duration.ZERO);
    var future = CompletableFuture.completedFuture((ResultMessage) terminal);

    var outcome = ResultLongPoll.awaitResult(future, LIVE, 5L, "sess-1");
    assertEquals(io.helidon.http.Status.OK_200, outcome.status());
    @SuppressWarnings("unchecked")
    var body = (Map<String, Object>) outcome.body();
    var resultObj = (ResultMessage.ErrorDuringExecution) body.get("result");
    assertTrue(resultObj.error().stackTrace().size() > 0, "frames preserved for diagnostics");
    assertEquals(carrying.message(), resultObj.error().message());
  }
}
