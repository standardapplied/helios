/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.fault.Backoff;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.TransientStreamException;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.Tool;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for the 2026-05-25 Light Grid report: {@code ProviderError: Stream read
 * error} terminated a long, expensive matchmaking run during the final structured-output emit turn.
 *
 * <p>This file pins the post-fix shape across the three behaviours the reporter asked for:
 *
 * <ol>
 *   <li>Cause-chain propagation — the wrapped {@link IOException} reaches the application via
 *       {@link ResultMessage.ErrorTransientStream#error()} with class name + message intact.
 *   <li>Bounded automatic retry — {@link SessionLimits#streamRetryPolicy()} re-issues the stream up
 *       to its attempt budget; the loop emits {@link QueryEvent.TurnRetried} between attempts.
 *   <li>Distinct terminal subtype — {@link ResultMessage.ErrorTransientStream} is a permitted
 *       sibling of {@link ResultMessage.ErrorDuringExecution}, so callers pattern-match the failure
 *       mode instead of string-matching the message.
 * </ol>
 */
class StreamReadErrorReproTest {

  /**
   * Mimics what a provider's {@code core.provider.ChatExchange} throws when the SSE reader catches
   * an {@code IOException} mid-stream: a {@link TransientStreamException} carrying the originating
   * {@code IOException} as its cause and the provider name in {@link
   * TransientStreamException#providerName()}.
   */
  private static final class TransientStreamFailureModel implements Model {

    final AtomicInteger chatStreamInvocations = new AtomicInteger(0);
    final TransientStreamException providerException;

    TransientStreamFailureModel(TransientStreamException providerException) {
      this.providerException = providerException;
    }

    @Override
    public Response<Void> chat(List<Message> messages, List<Tool> tools) {
      throw providerException;
    }

    @Override
    public Flow.Publisher<ModelChunk> chatStream(
        List<Message> messages, List<Tool> tools, CancellationToken cancellation) {
      chatStreamInvocations.incrementAndGet();
      throw providerException;
    }

    @Override
    public Flow.Publisher<ModelChunk> chatStream(
        List<Message> messages,
        List<Tool> tools,
        OutputSchema<?> outputSchema,
        CancellationToken cancellation) {
      chatStreamInvocations.incrementAndGet();
      throw providerException;
    }

    @Override
    public String id() {
      return "stream-read-error-fixture";
    }

    @Override
    public String provider() {
      return "anthropic";
    }
  }

  /**
   * The shape every transient stream error takes after the 2.5.5 fix: {@code
   * TransientStreamException("Stream read error", ioException, "anthropic")}.
   */
  private static TransientStreamException providerStyleException() {
    var ioe = new IOException("Connection reset by peer (peer closed connection at byte 27384)");
    return new TransientStreamException("Stream read error", ioe, "anthropic");
  }

  /**
   * No back-off between attempts so the tests stay sub-second. Production defaults are 1 s / 4 s
   * with ±25% jitter — see {@link StreamRetryPolicy#defaults()}.
   */
  private static SessionLimits limitsWithFastRetry(int attempts) {
    return SessionLimits.newBuilder()
        .withStreamRetryPolicy(new StreamRetryPolicy(attempts, Backoff.fixed(Duration.ZERO), 0.0))
        .build();
  }

  // ----------------------------------------------------------------------------------------------
  // Bug claim #1 — the underlying transport detail reaches the caller. Cause chain (kind =
  // throwable class name, message, recursive cause) is populated via SerializedError.of(throwable)
  // so deployers can distinguish a TCP reset from an idle timeout from a half-closed framing
  // error without string-matching the message field.
  // ----------------------------------------------------------------------------------------------

  @Test
  void transientStreamTerminalPreservesUnderlyingIoExceptionCauseChain() {
    var model = new TransientStreamFailureModel(providerStyleException());
    try (var session =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(model)
                .withSessionId("repro-cause-chain")
                .withLimits(limitsWithFastRetry(1))
                .build())) {
      var terminal = session.runBlocking(UserMessage.text("emit JSON"));
      var error = assertInstanceOf(ResultMessage.ErrorTransientStream.class, terminal);

      assertEquals("anthropic", error.providerName());
      assertEquals(1, error.attemptsMade(), "retry disabled — single attempt");

      var serialized = error.error();
      assertEquals(
          TransientStreamException.class.getName(),
          serialized.kind(),
          "kind reports the throwable class so callers can switch on the failure category");
      assertEquals("Stream read error", serialized.message());
      assertNotNull(serialized.cause(), "cause chain is populated");
      assertEquals(IOException.class.getName(), serialized.cause().kind());
      assertTrue(
          serialized.cause().message().contains("Connection reset"),
          "the IOException's specific message survives: " + serialized.cause().message());
      assertTrue(
          serialized.cause().message().contains("byte 27384"),
          "byte-offset detail surfaces too — same channel for any future transport diagnostics");
    }
  }

  // ----------------------------------------------------------------------------------------------
  // Bug claim #2 — bounded automatic retry. With the default policy, the loop re-issues the
  // stream up to its attempt budget before terminating.
  // ----------------------------------------------------------------------------------------------

  @Test
  void retryIssuesAttemptsUpToTheConfiguredBudget() {
    var model = new TransientStreamFailureModel(providerStyleException());
    try (var session =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(model)
                .withSessionId("repro-bounded-retry")
                .withLimits(limitsWithFastRetry(3))
                .build())) {
      var terminal = session.runBlocking(UserMessage.text("emit JSON"));
      var error = assertInstanceOf(ResultMessage.ErrorTransientStream.class, terminal);

      assertEquals(
          3,
          model.chatStreamInvocations.get(),
          "stream was retried up to the policy's maxAttempts");
      assertEquals(3, error.attemptsMade(), "terminal mirrors the attempt count");
    }
  }

  @Test
  void retryRecoversWhenStreamSucceedsBeforeBudgetExhaustion() {
    var failTwiceThenSucceed =
        new Model() {
          final AtomicInteger calls = new AtomicInteger(0);
          final TransientStreamException ex = providerStyleException();

          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            return Response.newBuilder().withContent("recovered").build();
          }

          @Override
          public Flow.Publisher<ModelChunk> chatStream(
              List<Message> messages, List<Tool> tools, CancellationToken cancellation) {
            var attempt = calls.incrementAndGet();
            if (attempt <= 2) {
              throw ex;
            }
            return subscriber ->
                subscriber.onSubscribe(
                    new Flow.Subscription() {
                      int i = 0;

                      @Override
                      public void request(long n) {
                        if (i == 0) {
                          subscriber.onNext(new ModelChunk.TextDelta("recovered"));
                          i = 1;
                        }
                        if (i == 1) {
                          subscriber.onNext(
                              new ModelChunk.MessageStop(
                                  FinishReason.STOP.name(), Usage.of(1, 1), Map.of()));
                          i = 2;
                          subscriber.onComplete();
                        }
                      }

                      @Override
                      public void cancel() {}
                    });
          }

          @Override
          public String id() {
            return "fail-twice-then-clean";
          }

          @Override
          public String provider() {
            return "anthropic";
          }
        };
    try (var session =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(failTwiceThenSucceed)
                .withSessionId("repro-retry-recovers")
                .withLimits(limitsWithFastRetry(3))
                .build())) {
      var terminal = session.runBlocking(UserMessage.text("emit JSON"));
      var success = assertInstanceOf(ResultMessage.Success.class, terminal);
      assertEquals("recovered", success.result());
      assertEquals(3, failTwiceThenSucceed.calls.get(), "third attempt succeeded");
    }
  }

  // ----------------------------------------------------------------------------------------------
  // Bug claim #3 — distinct terminal subtype. ErrorTransientStream is now a permitted sibling of
  // the existing terminals, so consumers can branch on its type with an exhaustive switch instead
  // of string-matching messages on the generic ErrorDuringExecution.
  // ----------------------------------------------------------------------------------------------

  @Test
  void transientStreamTerminalIsDistinguishableFromGenericProviderErrorTerminal() {
    var subtypes = ResultMessage.class.getPermittedSubclasses();
    boolean transientStreamFound = false;
    for (var sub : subtypes) {
      if (sub == ResultMessage.ErrorTransientStream.class) {
        transientStreamFound = true;
        break;
      }
    }
    assertTrue(
        transientStreamFound,
        "ResultMessage.ErrorTransientStream must be a permitted sibling of ResultMessage so"
            + " exhaustive switches recognise it without a default branch");

    var model = new TransientStreamFailureModel(providerStyleException());
    try (var session =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(model)
                .withSessionId("repro-distinct-subtype")
                .withLimits(limitsWithFastRetry(1))
                .build())) {
      var terminal = session.runBlocking(UserMessage.text("emit JSON"));
      assertInstanceOf(
          ResultMessage.ErrorTransientStream.class,
          terminal,
          "the loop terminates as ErrorTransientStream, not the generic ErrorDuringExecution");
    }
  }

  // ----------------------------------------------------------------------------------------------
  // Bug claim #4 — retry can be disabled. StreamRetryPolicy.disabled() turns retry off; the loop
  // attempts the stream exactly once and terminates immediately on the first transient failure.
  // ----------------------------------------------------------------------------------------------

  @Test
  void retryDisabledTerminatesOnFirstFailureWithoutRetrying() {
    var model = new TransientStreamFailureModel(providerStyleException());
    try (var session =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(model)
                .withSessionId("repro-retry-disabled")
                .withLimits(
                    SessionLimits.newBuilder()
                        .withStreamRetryPolicy(StreamRetryPolicy.disabled())
                        .build())
                .build())) {
      var terminal = session.runBlocking(UserMessage.text("emit JSON"));
      var error = assertInstanceOf(ResultMessage.ErrorTransientStream.class, terminal);

      assertEquals(1, model.chatStreamInvocations.get(), "single attempt with retry disabled");
      assertEquals(1, error.attemptsMade());
    }
  }

  // ----------------------------------------------------------------------------------------------
  // Bug claim #5 — cancellation during the back-off sleep aborts the loop cleanly, producing
  // ResultMessage.Cancelled instead of burning the remaining retries. The back-off is an hour, so
  // only the cancellation can end it.
  // ----------------------------------------------------------------------------------------------

  @Test
  void cancellationDuringBackoffShortCircuitsRetryAndProducesCancelledTerminal() {
    var model = new TransientStreamFailureModel(providerStyleException());
    var unreachableBackoff = new StreamRetryPolicy(5, Backoff.fixed(Duration.ofHours(1)), 0.0);
    try (var session =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(model)
                .withSessionId("repro-cancel-during-backoff")
                .withLimits(
                    SessionLimits.newBuilder().withStreamRetryPolicy(unreachableBackoff).build())
                .build())) {
      var retryAnnounced = new CountDownLatch(1);
      session.events().subscribe(new RetryWatcher(retryAnnounced));
      var run = new FutureTask<>(() -> session.runBlocking(UserMessage.text("emit JSON")));
      Thread.startVirtualThread(run);

      Await.latch("the loop to announce the retry it is about to back off for", retryAnnounced);
      session.interrupt("operator cancellation during retry back-off");
      session.close();

      var cancelled =
          assertInstanceOf(
              ResultMessage.Cancelled.class, Await.value("the cancelled run to return", run));
      assertEquals("session closed", cancelled.reason());
      assertEquals(
          1,
          model.chatStreamInvocations.get(),
          "cancellation must short-circuit the retry loop before a second attempt");
    }
  }

  /** Counts down when the loop emits {@link QueryEvent.TurnRetried}, just before it backs off. */
  private record RetryWatcher(CountDownLatch retryAnnounced)
      implements Flow.Subscriber<QueryEvent> {

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(QueryEvent event) {
      if (event instanceof QueryEvent.TurnRetried) {
        retryAnnounced.countDown();
      }
    }

    @Override
    public void onError(Throwable throwable) {}

    @Override
    public void onComplete() {}
  }
}
