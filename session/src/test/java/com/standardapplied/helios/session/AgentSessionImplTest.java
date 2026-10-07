/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.CostCalculator;
import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.schema.RawOutputCapturePolicy;
import com.standardapplied.helios.core.schema.StructuredOutputParseException;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.session.ask.AskUserQuestionResponse;
import com.standardapplied.helios.session.execution.ExecutionCapabilities;
import com.standardapplied.helios.session.execution.ExecutionProvider;
import com.standardapplied.helios.session.execution.ExecutionRequest;
import com.standardapplied.helios.session.execution.ExecutionResult;
import com.standardapplied.helios.session.execution.SessionStartOutcome;
import com.standardapplied.helios.session.hooks.PreStopHook;
import com.standardapplied.helios.session.test.CollectingSubscriber;
import com.standardapplied.helios.testing.ModelStreams;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

final class AgentSessionImplTest {

  private static final String SID = "sess-impl-1";
  private static final Instant FIXED = Instant.parse("2026-05-14T19:00:00Z");
  private static final Clock CLOCK = Clock.fixed(FIXED, ZoneOffset.UTC);

  private static AgentSession buildSession(Model model) {
    return buildSession(model, ConcurrencyLimits.defaults());
  }

  private static AgentSession buildSession(Model model, ConcurrencyLimits concurrency) {
    return AgentSession.create(
        SessionOptions.newBuilder()
            .withModel(model)
            .withSessionId(SID)
            .withConcurrencyLimits(concurrency)
            .withClock(CLOCK)
            .build());
  }

  private static Model textOnceModel(String reply, FinishReason finishReason) {
    return respondingWith(
        Response.newBuilder()
            .withContent(reply)
            .withFinishReason(finishReason)
            .withUsage(Usage.of(3, 2))
            .build());
  }

  private static Model respondingWith(Response<Void> response) {
    return ScriptedModel.newBuilder().withResponseTurn(response).build();
  }

  private static List<Class<?>> eventTypes(CollectingSubscriber sub) {
    return sub.events().stream().<Class<?>>map(QueryEvent::getClass).toList();
  }

  private static ResultMessage terminalOf(AgentSession session) {
    return Await.value("the session to reach its terminal", session.result());
  }

  // ── construction validation ───────────────────────────────────────────────

  @Test
  void constructorRejectsNullOptions() {
    var ex = assertThrows(NullPointerException.class, () -> new AgentSessionImpl(null));
    assertEquals("options must not be null", ex.getMessage());
  }

  @Test
  void createFactoryRejectsNullOptions() {
    var ex = assertThrows(NullPointerException.class, () -> AgentSession.create(null));
    assertEquals("options must not be null", ex.getMessage());
  }

  @Test
  void createFactoryReturnsAgentSession() {
    try (var s =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(textOnceModel("x", FinishReason.STOP))
                .withSessionId(SID)
                .withClock(CLOCK)
                .build())) {
      assertEquals(SID, s.sessionId());
    }
  }

  // ── accessor smoke ────────────────────────────────────────────────────────

  @Test
  void sessionIdAccessorReturnsOptionsValue() {
    try (var s = buildSession(textOnceModel("x", FinishReason.STOP))) {
      assertEquals(SID, s.sessionId());
    }
  }

  @Test
  void currentTurnIndexStartsAtZero() {
    try (var s = buildSession(textOnceModel("x", FinishReason.STOP))) {
      assertEquals(0, s.currentTurnIndex());
    }
  }

  @Test
  void eventsAccessorReturnsPublisher() {
    try (var s = buildSession(textOnceModel("x", FinishReason.STOP))) {
      assertNotNull(s.events());
    }
  }

  // ── send validation ──────────────────────────────────────────────────────

  @Test
  void sendRejectsNullMessage() {
    try (var s = buildSession(textOnceModel("x", FinishReason.STOP))) {
      var ex = assertThrows(NullPointerException.class, () -> s.send((UserMessage) null));
      assertEquals("message must not be null", ex.getMessage());
    }
  }

  @Test
  void sendOnClosedSessionThrows() {
    var s = buildSession(textOnceModel("x", FinishReason.STOP));
    s.close();
    var ex = assertThrows(IllegalStateException.class, () -> s.send(UserMessage.text("hi")));
    assertEquals("session is closed", ex.getMessage());
  }

  @Test
  void sendOnTerminalSessionThrows() throws Exception {
    var s = buildSession(textOnceModel("done", FinishReason.STOP));
    s.send(UserMessage.text("hi"));
    terminalOf(s);
    var ex = assertThrows(IllegalStateException.class, () -> s.send(UserMessage.text("again")));
    assertEquals("session is terminal", ex.getMessage());
    s.close();
  }

  @Test
  void sendFullQueueThrows() throws Exception {
    var tinyConcurrency = new ConcurrencyLimits(32, 4, 2, 2);
    var release = new CountDownLatch(1);
    var entered = new CountDownLatch(1);
    Model latched = latchedModel(entered, release, "ok");
    var s = buildSession(latched, tinyConcurrency);
    try {
      s.send(UserMessage.text("first"));
      Await.latch("the loop to reach chat()", entered);
      s.send(UserMessage.text("second"));
      s.send(UserMessage.text("third"));
      var ex = assertThrows(IllegalStateException.class, () -> s.send(UserMessage.text("fourth")));
      assertTrue(ex.getMessage().startsWith("steering queue full"));
    } finally {
      release.countDown();
      s.close();
    }
  }

  @Test
  void interruptOnFullQueueThrows() throws Exception {
    var tinyConcurrency = new ConcurrencyLimits(32, 4, 2, 1);
    var release = new CountDownLatch(1);
    var entered = new CountDownLatch(1);
    Model latched = latchedModel(entered, release, "ok");
    var s = buildSession(latched, tinyConcurrency);
    try {
      s.send(UserMessage.text("first"));
      Await.latch("the loop to reach chat()", entered);
      s.send(UserMessage.text("second"));
      var ex = assertThrows(IllegalStateException.class, () -> s.interrupt("nope"));
      assertTrue(ex.getMessage().contains("cannot enqueue interrupt"));
    } finally {
      release.countDown();
      s.close();
    }
  }

  // ── interrupt validation ─────────────────────────────────────────────────

  @Test
  void interruptRejectsNullReason() {
    try (var s = buildSession(textOnceModel("x", FinishReason.STOP))) {
      var ex = assertThrows(NullPointerException.class, () -> s.interrupt(null));
      assertEquals("reason must not be null", ex.getMessage());
    }
  }

  @Test
  void interruptRejectsBlankReason() {
    try (var s = buildSession(textOnceModel("x", FinishReason.STOP))) {
      var ex = assertThrows(IllegalArgumentException.class, () -> s.interrupt("  "));
      assertEquals("reason must not be blank", ex.getMessage());
    }
  }

  @Test
  void interruptOnClosedSessionThrows() {
    var s = buildSession(textOnceModel("x", FinishReason.STOP));
    s.close();
    assertThrows(IllegalStateException.class, () -> s.interrupt("nope"));
  }

  @Test
  void interruptOnTerminalSessionThrows() throws Exception {
    var s = buildSession(textOnceModel("done", FinishReason.STOP));
    s.send(UserMessage.text("hi"));
    terminalOf(s);
    assertThrows(IllegalStateException.class, () -> s.interrupt("late"));
    s.close();
  }

  // ── happy path ────────────────────────────────────────────────────────────

  @Test
  void singleMessageProducesSuccessAndStreamCompletes() throws Exception {
    try (var s = buildSession(textOnceModel("hello back", FinishReason.STOP))) {
      var sub = new CollectingSubscriber();
      s.events().subscribe(sub);
      s.send(UserMessage.text("hi"));

      var result = terminalOf(s);
      sub.awaitDone();

      var success = assertInstanceOf(ResultMessage.Success.class, result);
      assertEquals("hello back", success.result());
      assertTrue(sub.events().stream().anyMatch(e -> e instanceof QueryEvent.UserMessageReceived));
      assertTrue(sub.events().stream().anyMatch(e -> e instanceof QueryEvent.AssistantText));
      assertTrue(sub.events().stream().anyMatch(e -> e instanceof QueryEvent.LoopEnded));
    }
  }

  @Test
  void runBlockingDrivesSendAndAwait() {
    try (var s = buildSession(textOnceModel("done", FinishReason.STOP))) {
      var result = s.runBlocking(UserMessage.text("hi"));
      var success = assertInstanceOf(ResultMessage.Success.class, result);
      assertEquals("done", success.result());
    }
  }

  // ── interrupt steering ────────────────────────────────────────────────────

  @Test
  void interruptQueuesSyntheticMessageAndContinues() throws Exception {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var firstTurnEntered = new CountDownLatch(1);
    var firstTurnRelease = new CountDownLatch(1);
    Model alternating =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            var call = calls.incrementAndGet();
            if (call == 1) {
              // Hold the first turn until interrupt() is queued: a first turn that completes
              // with an empty queue sends the session terminal, and interrupt() then throws.
              firstTurnEntered.countDown();
              try {
                firstTurnRelease.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            }
            return Response.newBuilder()
                .withContent("turn-" + call)
                .withFinishReason(FinishReason.STOP)
                .withUsage(Usage.of(1, 1))
                .build();
          }

          @Override
          public String id() {
            return "test";
          }

          @Override
          public String provider() {
            return "test";
          }
        };
    try (var s = buildSession(alternating)) {
      var sub = new CollectingSubscriber();
      s.events().subscribe(sub);
      s.send(UserMessage.text("first"));
      Await.latch("the first turn to reach chat()", firstTurnEntered);
      s.interrupt("rethink");
      firstTurnRelease.countDown();
      var result = terminalOf(s);
      sub.awaitDone();

      var success = assertInstanceOf(ResultMessage.Success.class, result);
      var interruptedReceived =
          sub.events().stream()
              .filter(e -> e instanceof QueryEvent.UserMessageReceived)
              .map(e -> (QueryEvent.UserMessageReceived) e)
              .anyMatch(u -> u.message().text().contains("[interrupted by user: rethink]"));
      assertTrue(interruptedReceived, "interrupt synthetic message must be received");
      assertEquals("turn-2", success.result());
      assertEquals(2, calls.get());
    }
  }

  // ── close lifecycle ───────────────────────────────────────────────────────

  @Test
  void closeBeforeAnySendProducesCancelledTerminal() throws Exception {
    var s = buildSession(textOnceModel("never", FinishReason.STOP));
    s.close();
    var result = terminalOf(s);
    var c = assertInstanceOf(ResultMessage.Cancelled.class, result);
    assertEquals("session closed", c.reason());
  }

  @Test
  void closeIsIdempotent() throws Exception {
    var s = buildSession(textOnceModel("x", FinishReason.STOP));
    s.close();
    s.close();
    s.close();
    assertInstanceOf(ResultMessage.Cancelled.class, terminalOf(s));
  }

  @Test
  void closeAfterTerminalDoesNotBreak() throws Exception {
    var s = buildSession(textOnceModel("done", FinishReason.STOP));
    s.send(UserMessage.text("hi"));
    var first = terminalOf(s);
    s.close();
    assertEquals(first, terminalOf(s));
  }

  @Test
  void closeDuringRunningLoopProducesCancelled() {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var s = buildSession(latchedModel(entered, release, "too late"))) {
      s.send(UserMessage.text("hi"));
      Await.latch("the loop to reach chat()", entered);
      s.close();
      release.countDown();
      var cancelled = assertInstanceOf(ResultMessage.Cancelled.class, terminalOf(s));
      assertEquals("session closed", cancelled.reason());
    }
  }

  @Test
  void closeBeforeAnySendShutsDownPublisherExecutor() {
    var s = (AgentSessionImpl) buildSession(textOnceModel("x", FinishReason.STOP));
    var executor = s.lifecycleForTests().publisherExecutor();
    assertTrue(!executor.isShutdown(), "executor live before close()");
    s.close();
    assertTrue(executor.isShutdown(), "executor shut down by close()");
    assertTrue(executor.isTerminated(), "executor terminated by close()");
  }

  @Test
  void naturalLoopTerminationShutsDownPublisherExecutor() throws Exception {
    var s = (AgentSessionImpl) buildSession(textOnceModel("done", FinishReason.STOP));
    var executor = s.lifecycleForTests().publisherExecutor();
    s.send(UserMessage.text("hi"));
    terminalOf(s);
    // closeRuntime() runs BEFORE resultFuture settles (hv2-bug2 Issue 2 fix), so the executor is
    // already terminated by the time the result is available.
    assertTrue(executor.isShutdown(), "executor shut down after natural termination");
    assertTrue(executor.isTerminated(), "executor terminated after natural termination");
  }

  @Test
  void resultFutureExposedAsCompletableFuture() {
    try (var s = buildSession(textOnceModel("x", FinishReason.STOP))) {
      var f = s.result();
      assertInstanceOf(CompletableFuture.class, f);
    }
  }

  @Test
  void modelThatThrowsSynchronouslyProducesErrorTerminal() throws Exception {
    Model throwing =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new RuntimeException("model boom");
          }

          @Override
          public String id() {
            return "test";
          }

          @Override
          public String provider() {
            return "test";
          }
        };
    try (var s = buildSession(throwing)) {
      var sub = new CollectingSubscriber();
      s.events().subscribe(sub);
      s.send(UserMessage.text("hi"));
      var result = terminalOf(s);
      assertInstanceOf(ResultMessage.ErrorDuringExecution.class, result);
    }
  }

  @Test
  void errorEscapingAgentLoopSettlesFutureExceptionallyInsteadOfHanging() {
    // AgentLoop.run catches Exception (not Throwable); HookRegistry catches RuntimeException (not
    // Throwable). So an Error subtype thrown from a hook escapes both defensive catches and lands
    // in AgentSessionImpl.runLoop's outer catch. Without that catch, resultFuture never completes
    // and every caller blocked on result().join() hangs indefinitely.
    PreStopHook erroring =
        (response, ctx) -> {
          throw new AssertionError("simulated unrecoverable error");
        };
    try (var s =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(textOnceModel("done", FinishReason.STOP))
                .withSessionId(SID)
                .withClock(CLOCK)
                .withHook(erroring)
                .build())) {
      s.send(UserMessage.text("hi"));
      var failure = Await.failure("the result future to settle exceptionally", s.result());
      assertInstanceOf(AssertionError.class, failure);
      assertEquals("simulated unrecoverable error", failure.getMessage());
    }
  }

  // ── execution-provider lifecycle (onSessionStart / onSessionEnd) ─────────

  /** Stub provider that observes start / end and can be configured to refuse or throw. */
  private static final class LifecycleProvider implements ExecutionProvider {
    final AtomicBoolean startSeen = new AtomicBoolean();
    final AtomicBoolean endSeen = new AtomicBoolean();
    SessionStartOutcome startOutcome = SessionStartOutcome.accept();
    RuntimeException throwOnStart;
    RuntimeException throwOnEnd;

    @Override
    public ExecutionCapabilities capabilities() {
      return ExecutionCapabilities.newBuilder().build();
    }

    @Override
    public SessionStartOutcome onSessionStart(SessionContext ctx) {
      startSeen.set(true);
      if (throwOnStart != null) {
        throw throwOnStart;
      }
      return startOutcome;
    }

    @Override
    public void onSessionEnd(SessionContext ctx) {
      endSeen.set(true);
      if (throwOnEnd != null) {
        throw throwOnEnd;
      }
    }

    @Override
    public CompletionStage<ExecutionResult> execute(
        SessionContext session, ExecutionRequest request, CancellationToken cancellation) {
      throw new AssertionError("not used");
    }
  }

  @Test
  void providerRefuseProducesErrorProviderUnavailableTerminal() throws Exception {
    var provider = new LifecycleProvider();
    provider.startOutcome = SessionStartOutcome.refuse("pool saturated");
    try (var s =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(textOnceModel("unused", FinishReason.STOP))
                .withSessionId(SID)
                .withClock(CLOCK)
                .withExecutionProvider(provider)
                .build())) {
      assertTrue(provider.startSeen.get());
      var terminal = terminalOf(s);
      var err = assertInstanceOf(ResultMessage.ErrorProviderUnavailable.class, terminal);
      assertEquals("pool saturated", err.reason());
      assertEquals("LifecycleProvider", err.providerName());
      // send() against a refused (terminal) session throws.
      assertThrows(IllegalStateException.class, () -> s.send(UserMessage.text("hi")));
    }
    // onSessionEnd never fires when the provider refused at start.
    assertFalse(provider.endSeen.get());
  }

  @Test
  void providerOnSessionStartExceptionWithoutAMessageIsReportedAsNoMessage() throws Exception {
    var provider = new LifecycleProvider();
    provider.throwOnStart = new IllegalStateException();
    try (var s =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(textOnceModel("unused", FinishReason.STOP))
                .withSessionId(SID)
                .withClock(CLOCK)
                .withExecutionProvider(provider)
                .build())) {
      var err = assertInstanceOf(ResultMessage.ErrorProviderUnavailable.class, terminalOf(s));
      assertEquals("onSessionStart threw IllegalStateException: (no message)", err.reason());
    }
  }

  @Test
  void systemPromptLeadsTheHistoryTheModelSees() {
    var model = ScriptedModel.newBuilder().withTextTurn("ok").build();
    try (var s =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(model)
                .withSessionId(SID)
                .withClock(CLOCK)
                .withSystemPrompt("be terse")
                .build())) {
      s.runBlocking(UserMessage.text("hi"));
    }
    var seen = model.calls().getFirst();
    assertEquals(List.of("be terse", "hi"), seen.stream().map(Message::content).toList());
    assertEquals(com.standardapplied.helios.core.model.Role.SYSTEM, seen.getFirst().role());
  }

  @Test
  void providerOnSessionStartRuntimeExceptionProducesErrorProviderUnavailable() throws Exception {
    var provider = new LifecycleProvider();
    provider.throwOnStart = new RuntimeException("auth failed");
    try (var s =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(textOnceModel("unused", FinishReason.STOP))
                .withSessionId(SID)
                .withClock(CLOCK)
                .withExecutionProvider(provider)
                .build())) {
      var terminal = terminalOf(s);
      var err = assertInstanceOf(ResultMessage.ErrorProviderUnavailable.class, terminal);
      assertTrue(err.reason().contains("auth failed"));
      assertTrue(err.reason().contains("RuntimeException"));
      // The thrown RuntimeException is preserved as the typed cause so deployers can inspect its
      // class, message, and stack trace without parsing the reason string.
      assertTrue(err.causeOpt().isPresent(), "thrown exception must be captured as cause");
      assertEquals(RuntimeException.class.getName(), err.causeOpt().orElseThrow().kind());
      assertEquals("auth failed", err.causeOpt().orElseThrow().message());
    }
    assertFalse(provider.endSeen.get());
  }

  /**
   * The motivating bug for 2.5.2: when a provider's {@code onSessionStart} returns a {@link
   * SessionStartOutcome#refuse(String, Throwable) refuse-with-cause}, the resulting {@link
   * ResultMessage.ErrorProviderUnavailable} must carry the full {@link SerializedError} cause chain
   * so deployers can drill into the root cause (e.g. the original {@code IOException} from {@code
   * ProcessBuilder.start()}) instead of seeing only the outer wrapper's message.
   */
  @Test
  void providerRefuseWithCauseSurfacesFullSerializedErrorChain() throws Exception {
    var rootCause = new java.io.IOException("Cannot run program 'java': No such file or directory");
    var midWrapper = new RuntimeException("Failed to start JVM sandbox subprocess", rootCause);
    var provider = new LifecycleProvider();
    provider.startOutcome = SessionStartOutcome.refuse("failed to spawn sandbox", midWrapper);

    try (var s =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(textOnceModel("unused", FinishReason.STOP))
                .withSessionId(SID)
                .withClock(CLOCK)
                .withExecutionProvider(provider)
                .build())) {
      var terminal = terminalOf(s);
      var err = assertInstanceOf(ResultMessage.ErrorProviderUnavailable.class, terminal);
      assertEquals("failed to spawn sandbox", err.reason());

      var cause = err.causeOpt().orElseThrow(() -> new AssertionError("cause must be present"));
      assertEquals(RuntimeException.class.getName(), cause.kind());
      assertEquals("Failed to start JVM sandbox subprocess", cause.message());

      var rootSerialised = cause.causeOpt().orElseThrow(() -> new AssertionError("root cause"));
      assertEquals(java.io.IOException.class.getName(), rootSerialised.kind());
      assertEquals(
          "Cannot run program 'java': No such file or directory",
          rootSerialised.message(),
          "the original IOException's message must reach the deployer untouched");
    }
  }

  @Test
  void providerOnSessionEndFiresOnSuccessfulTerminal() throws Exception {
    var provider = new LifecycleProvider();
    try (var s =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(textOnceModel("done", FinishReason.STOP))
                .withSessionId(SID)
                .withClock(CLOCK)
                .withExecutionProvider(provider)
                .build())) {
      assertTrue(provider.startSeen.get());
      s.send(UserMessage.text("hi"));
      assertInstanceOf(ResultMessage.Success.class, terminalOf(s));
      assertTrue(provider.endSeen.get(), "onSessionEnd must fire before the result settles");
    }
  }

  @Test
  void providerOnSessionEndFiresOnPreStartClose() throws Exception {
    // Loop never starts (no send/interrupt) — close() is the only signal.
    var provider = new LifecycleProvider();
    try (var s =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(textOnceModel("unused", FinishReason.STOP))
                .withSessionId(SID)
                .withClock(CLOCK)
                .withExecutionProvider(provider)
                .build())) {
      // intentionally no send — just close
      var _unused = s;
    }
    assertTrue(provider.endSeen.get(), "onSessionEnd must fire on pre-start close");
  }

  @Test
  void providerOnSessionEndThrowingRuntimeExceptionIsSwallowed() throws Exception {
    var provider = new LifecycleProvider();
    provider.throwOnEnd = new RuntimeException("end-cleanup-boom");
    try (var s =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(textOnceModel("done", FinishReason.STOP))
                .withSessionId(SID)
                .withClock(CLOCK)
                .withExecutionProvider(provider)
                .build())) {
      s.send(UserMessage.text("hi"));
      // Terminal must still be Success — onSessionEnd exception swallowed and logged.
      assertInstanceOf(ResultMessage.Success.class, terminalOf(s));
      assertTrue(provider.endSeen.get());
    }
  }

  @Test
  void providerOnSessionEndFailureIsLoggedAsAWarningOnTheSessionLogger() {
    var provider = new LifecycleProvider();
    provider.throwOnEnd = new RuntimeException("end-cleanup-boom");
    var logged = new CopyOnWriteArrayList<LogRecord>();
    var logger = Logger.getLogger(AgentSessionImpl.class.getName());
    var handler =
        new Handler() {
          @Override
          public void publish(LogRecord logRecord) {
            logged.add(logRecord);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    logger.addHandler(handler);
    try {
      AgentSession.create(
              SessionOptions.newBuilder()
                  .withModel(textOnceModel("unused", FinishReason.STOP))
                  .withSessionId(SID)
                  .withClock(CLOCK)
                  .withExecutionProvider(provider)
                  .build())
          .close();
    } finally {
      logger.removeHandler(handler);
    }

    assertEquals(1, logged.size());
    var warning = logged.getFirst();
    assertEquals(Level.WARNING, warning.getLevel());
    assertEquals("onSessionEnd threw — continuing shutdown", warning.getMessage());
    assertSame(provider.throwOnEnd, warning.getThrown());
  }

  // ── publisher-drain happens-before result settling (hv2-bug2 Issue 2) ────

  /**
   * Subscriber that is slow exactly where the hv2-bug2 Issue 2 race lives: it holds {@code
   * LoopEnded} until the session has begun draining its publisher, records whether the result had
   * already settled by then, and only then captures the terminal. Pre-fix the session settled the
   * result before draining, so {@link #resultSettledFirst} is deterministically {@code true}.
   */
  private static final class DrainObservingSubscriber implements Flow.Subscriber<QueryEvent> {

    final AtomicReference<ResultMessage> captured = new AtomicReference<>();
    final AtomicBoolean resultSettledFirst = new AtomicBoolean();
    private final AgentSessionImpl session;

    DrainObservingSubscriber(AgentSession session) {
      this.session = (AgentSessionImpl) session;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(QueryEvent event) {
      if (event instanceof QueryEvent.LoopEnded ended) {
        Await.until(
            "the session to begin draining its publisher",
            session.lifecycleForTests().publisherExecutor()::isShutdown);
        resultSettledFirst.set(session.result().isDone());
        captured.set(ended.result());
      }
    }

    @Override
    public void onError(Throwable throwable) {}

    @Override
    public void onComplete() {}

    void assertObservedLoopEndedBeforeResult(ResultMessage terminal) {
      assertEquals(
          terminal,
          captured.get(),
          "subscriber must observe LoopEnded happens-before the result is available — without "
              + "this guarantee deployer-side usage/cost capture races and silently drops data");
      assertFalse(
          resultSettledFirst.get(), "the result must not settle while a subscriber is draining");
    }
  }

  @Test
  void subscriberObservesLoopEndedBeforeRunBlockingReturns() {
    // Regression for hv2-bug2 Issue 2: a subscriber that captures LoopEnded for usage/cost
    // observability must see the event BEFORE any caller of runBlocking / result().get()
    // unblocks. Pre-fix the publisher executor drained asynchronously after the result future
    // resolved, so an immediate read of the AtomicReference would still observe null. 3 of 24
    // viewers in the Light Grid matchmaking baseline silently dropped cost data this way.
    try (var s = buildSession(textOnceModel("done", FinishReason.STOP))) {
      var subscriber = new DrainObservingSubscriber(s);
      s.events().subscribe(subscriber);
      var terminal = s.runBlocking(UserMessage.text("hi"));
      subscriber.assertObservedLoopEndedBeforeResult(terminal);
    }
  }

  @Test
  void subscriberObservesLoopEndedBeforeResultFutureCompletes() {
    // Same happens-before contract from the result()-future side. Asserted from the
    // result-future code path because runBlocking is a thin default; some deployers call
    // result().get() directly (e.g. when wrapping the session in their own runtime).
    try (var s = buildSession(textOnceModel("done", FinishReason.STOP))) {
      var subscriber = new DrainObservingSubscriber(s);
      s.events().subscribe(subscriber);
      s.send(UserMessage.text("hi"));
      var terminal = terminalOf(s);
      subscriber.assertObservedLoopEndedBeforeResult(terminal);
    }
  }

  @Test
  void multipleSlowSubscribersAllObserveLoopEndedBeforeRunBlockingReturns() {
    // Two subscribers both slow. The drain must wait for ALL of them, not just the first.
    try (var s = buildSession(textOnceModel("done", FinishReason.STOP))) {
      var first = new DrainObservingSubscriber(s);
      var second = new DrainObservingSubscriber(s);
      s.events().subscribe(first);
      s.events().subscribe(second);
      var terminal = s.runBlocking(UserMessage.text("hi"));
      first.assertObservedLoopEndedBeforeResult(terminal);
      second.assertObservedLoopEndedBeforeResult(terminal);
    }
  }

  @Test
  void multipleSubscribersAllReceiveEvents() throws Exception {
    try (var s = buildSession(textOnceModel("hello", FinishReason.STOP))) {
      var sub1 = new CollectingSubscriber();
      var sub2 = new CollectingSubscriber();
      s.events().subscribe(sub1);
      s.events().subscribe(sub2);
      s.send(UserMessage.text("hi"));
      terminalOf(s);
      sub1.awaitDone();
      sub2.awaitDone();
      assertEquals(
          List.of(
              QueryEvent.UserMessageReceived.class,
              QueryEvent.AssistantText.class,
              QueryEvent.TurnEnded.class,
              QueryEvent.LoopEnded.class),
          eventTypes(sub1));
      assertEquals(sub1.events(), sub2.events(), "both subscribers see the same stream");
    }
  }

  @Test
  void resultStaysPendingWhileTheModelIsBlocked() {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var s = buildSession(latchedModel(entered, release, "late"))) {
      s.send(UserMessage.text("hi"));
      Await.latch("the loop to reach chat()", entered);
      assertFalse(s.result().isDone(), "the result cannot settle while the model call is open");
      release.countDown();
      var success = assertInstanceOf(ResultMessage.Success.class, terminalOf(s));
      assertEquals("late", success.result());
    }
  }

  @Test
  void timestampOnPublishedEventsCarriesClock() throws Exception {
    try (var s = buildSession(textOnceModel("hi", FinishReason.STOP))) {
      var sub = new CollectingSubscriber();
      s.events().subscribe(sub);
      s.send(UserMessage.text("hi"));
      terminalOf(s);
      sub.awaitDone();
      for (var e : sub.events()) {
        assertEquals(FIXED, e.timestamp());
      }
    }
  }

  // ── typed runBlocking ────────────────────────────────────────────────────

  /** Output record used by the typed-runBlocking tests below. */
  public record TypedAnswer(String name, int score) {}

  @Test
  void typedRunBlockingParsesFinalAssistantTextAgainstSchema() {
    var json = "{\"name\":\"alice\",\"score\":42}";
    try (var s = buildSession(textOnceModel(json, FinishReason.STOP))) {
      var result = s.runBlocking(UserMessage.text("hi"), OutputSchema.of(TypedAnswer.class));
      assertEquals("alice", result.name());
      assertEquals(42, result.score());
    }
  }

  @Test
  void typedRunBlockingToleratesMarkdownFences() {
    var fenced = "```json\n{\"name\":\"bob\",\"score\":7}\n```";
    try (var s = buildSession(textOnceModel(fenced, FinishReason.STOP))) {
      var result = s.runBlocking(UserMessage.text("hi"), OutputSchema.of(TypedAnswer.class));
      assertEquals("bob", result.name());
      assertEquals(7, result.score());
    }
  }

  @Test
  void typedRunBlockingInheritsDisabledRawOutputCaptureFromModel() {
    var canary = "session-raw-output-canary";
    var delegate = textOnceModel("{\"name\":\"" + canary + "\"}", FinishReason.STOP);
    Model privacyModel =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            return delegate.chat(messages, tools);
          }

          @Override
          public String id() {
            return delegate.id();
          }

          @Override
          public String provider() {
            return delegate.provider();
          }

          @Override
          public RawOutputCapturePolicy rawOutputCapturePolicy() {
            return RawOutputCapturePolicy.DISABLED;
          }
        };

    try (var session = buildSession(privacyModel)) {
      var error =
          assertThrows(
              StructuredOutputParseException.class,
              () ->
                  session.runBlocking(UserMessage.text("hi"), OutputSchema.of(TypedAnswer.class)));

      assertNull(error.rawContent());
      assertFalse(error.getMessage().contains(canary));
    }
  }

  @Test
  void typedRunBlockingRejectsNullMessage() {
    try (var s = buildSession(textOnceModel("{}", FinishReason.STOP))) {
      assertThrows(
          NullPointerException.class,
          () -> s.runBlocking(null, OutputSchema.of(TypedAnswer.class)));
    }
  }

  @Test
  void typedRunBlockingRejectsNullSchema() {
    try (var s = buildSession(textOnceModel("{}", FinishReason.STOP))) {
      assertThrows(
          NullPointerException.class,
          () -> s.runBlocking(UserMessage.text("hi"), (OutputSchema<?>) null));
    }
  }

  @Test
  void typedRunBlockingThrowsOnNonSuccessTerminal() {
    // CONTENT_FILTER produces a Refusal terminal — typed runBlocking has nothing to parse.
    try (var s = buildSession(textOnceModel("model refusal", FinishReason.CONTENT_FILTER))) {
      var ex =
          assertThrows(
              IllegalStateException.class,
              () -> s.runBlocking(UserMessage.text("hi"), OutputSchema.of(TypedAnswer.class)));
      assertTrue(ex.getMessage().contains("Refusal"));
    }
  }

  // ── provider-reported refusal detail and thinking reach the session surface ──

  @Test
  void refusalTerminalCarriesTheProviderCategoryAndExplanation() {
    var declined =
        Response.newBuilder()
            .withContent("")
            .withFinishReason(FinishReason.REFUSAL)
            .withUsage(Usage.of(412, 0))
            .withMetadata(
                Map.of(
                    Response.REFUSAL_CATEGORY_KEY,
                    "cyber",
                    Response.REFUSAL_EXPLANATION_KEY,
                    "This request was declined because it could enable cyber harm."))
            .build();

    try (var s = buildSession(respondingWith(declined))) {
      var refusal =
          assertInstanceOf(ResultMessage.Refusal.class, s.runBlocking(UserMessage.text("hi")));

      assertEquals("cyber", refusal.category());
      assertEquals(
          "This request was declined because it could enable cyber harm.", refusal.refusalText());
      assertEquals(412, refusal.usage().inputTokens());
    }
  }

  @Test
  void thinkingFromABlockingModelSurfacesAsAssistantThinkingBeforeTheText() throws Exception {
    var thoughtful =
        Response.newBuilder()
            .withContent("Top match: profile 12.")
            .withThinking("Ranked the candidates. Reading profile 12 next.")
            .withFinishReason(FinishReason.STOP)
            .withUsage(Usage.of(3, 2))
            .build();

    try (var s = buildSession(respondingWith(thoughtful))) {
      var sub = new CollectingSubscriber();
      s.events().subscribe(sub);
      s.send(UserMessage.text("match me"));
      terminalOf(s);
      sub.awaitDone();

      var kinds =
          sub.events().stream()
              .filter(
                  e ->
                      e instanceof QueryEvent.AssistantThinking
                          || e instanceof QueryEvent.AssistantText)
              .toList();
      assertEquals(2, kinds.size());
      var thinking = assertInstanceOf(QueryEvent.AssistantThinking.class, kinds.get(0));
      assertEquals("Ranked the candidates. Reading profile 12 next.", thinking.text());
      assertInstanceOf(QueryEvent.AssistantText.class, kinds.get(1));
    }
  }

  // ── maxBudgetMicroUsd integration ────────────────────────────────────────

  @Test
  void costCalculatorAccumulatesAndExceedingBudgetProducesErrorMaxBudgetTerminal() {
    // Pricing: $1.00 / Mtok input, $1.00 / Mtok output. One turn of 1M+1M tokens => $2.00.
    // Budget: $1.50. Expectation: ErrorMaxBudgetUsd terminal with microUsdSpent == 2_000_000.
    var calc =
        CostCalculator.staticTable(
            Map.of("test", CostCalculator.Pricing.ofUsdPerMillion(1.0, 1.0)));
    var limits = SessionLimits.newBuilder().withMaxBudgetMicroUsd(1_500_000L).build();
    var bigUsage = Usage.of(1_000_000, 1_000_000);
    var billy =
        ScriptedModel.newBuilder()
            .withId("test")
            .withResponseTurn(
                Response.newBuilder()
                    .withContent("here")
                    .withFinishReason(FinishReason.STOP)
                    .withUsage(bigUsage)
                    .build())
            .build();
    try (var s =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(billy)
                .withSessionId(SID)
                .withClock(CLOCK)
                .withLimits(limits)
                .withCostCalculator(calc)
                .build())) {
      var terminal = s.runBlocking(UserMessage.text("hi"));
      var budget = assertInstanceOf(ResultMessage.ErrorMaxBudgetUsd.class, terminal);
      assertEquals(2_000_000L, budget.microUsdSpent());
      assertEquals(2_000_000L, budget.cost().microUsd());
    }
  }

  @Test
  void defaultCostCalculatorIsZeroSoBudgetNeverFires() {
    // No withCostCalculator(...) call. Even with a tight budget, cost stays $0 and the session
    // completes normally — this is the opt-in contract.
    var limits = SessionLimits.newBuilder().withMaxBudgetMicroUsd(10_000L).build();
    try (var s =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(textOnceModel("hello", FinishReason.STOP))
                .withSessionId(SID)
                .withClock(CLOCK)
                .withLimits(limits)
                .build())) {
      var terminal = s.runBlocking(UserMessage.text("hi"));
      assertInstanceOf(ResultMessage.Success.class, terminal);
      assertEquals(CostEstimate.zero(), terminal.cost());
    }
  }

  // ── answer() validation ──────────────────────────────────────────────────

  @Test
  void answerRejectsNullQuestionId() {
    try (var s = buildSession(textOnceModel("hi", FinishReason.STOP))) {
      assertThrows(
          NullPointerException.class,
          () -> s.answer(null, AskUserQuestionResponse.single("q", "ok")));
    }
  }

  @Test
  void answerRejectsBlankQuestionId() {
    try (var s = buildSession(textOnceModel("hi", FinishReason.STOP))) {
      assertThrows(
          IllegalArgumentException.class,
          () -> s.answer("  ", AskUserQuestionResponse.single("q", "ok")));
    }
  }

  @Test
  void answerRejectsNullResponse() {
    try (var s = buildSession(textOnceModel("hi", FinishReason.STOP))) {
      assertThrows(NullPointerException.class, () -> s.answer("q-1", null));
    }
  }

  @Test
  void answerRejectsMismatchedResponseQuestionId() {
    try (var s = buildSession(textOnceModel("hi", FinishReason.STOP))) {
      var ex =
          assertThrows(
              IllegalArgumentException.class,
              () -> s.answer("q-1", AskUserQuestionResponse.single("q-OTHER", "x")));
      assertTrue(ex.getMessage().contains("does not match"));
    }
  }

  @Test
  void answerOnClosedSessionThrows() {
    var s = buildSession(textOnceModel("hi", FinishReason.STOP));
    s.close();
    assertThrows(
        IllegalStateException.class,
        () -> s.answer("q-1", AskUserQuestionResponse.single("q-1", "ok")));
  }

  @Test
  void answerForUnknownQuestionIdThrows() {
    try (var s = buildSession(textOnceModel("hi", FinishReason.STOP))) {
      var ex =
          assertThrows(
              IllegalArgumentException.class,
              () -> s.answer("q-unknown", AskUserQuestionResponse.single("q-unknown", "ok")));
      assertTrue(ex.getMessage().contains("no pending question"));
    }
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  /** Model whose chat() awaits {@code release} after signalling {@code entered}. */
  private static Model latchedModel(CountDownLatch entered, CountDownLatch release, String reply) {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        entered.countDown();
        try {
          release.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return Response.newBuilder()
            .withContent(reply)
            .withFinishReason(FinishReason.STOP)
            .withUsage(Usage.of(1, 1))
            .build();
      }

      @Override
      public String id() {
        return "test";
      }

      @Override
      public String provider() {
        return "test";
      }
    };
  }

  // ── outputSchema rides every model turn via the provider's native channel ──

  /** Sample record so we can build a real {@link OutputSchema} for the wiring tests. */
  public record Sample(String field) {}

  /** A model whose one turn streams {@code json} as a finished text answer. */
  private static ScriptedModel streamingJson(String json) {
    return ScriptedModel.newBuilder()
        .withStreamTurn(
            ModelStreams.of(
                new ModelChunk.TextDelta(json),
                new ModelChunk.MessageStop(FinishReason.STOP.name(), Usage.of(1, 1), Map.of())))
        .build();
  }

  @Test
  void configuredOutputSchemaRidesEveryTurnAndCarriesThroughToTheProvider() throws Exception {
    var schema = OutputSchema.of(Sample.class);
    var model = streamingJson("{\"field\":\"typed-with-schema\"}");
    try (var session =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(model)
                .withSessionId(SID)
                .withClock(CLOCK)
                .withOutputSchema(schema)
                .build())) {
      var typed = session.runBlocking(UserMessage.text("go"), schema);
      assertEquals("typed-with-schema", typed.field());
    }
    var schemas = model.outputSchemas();
    assertTrue(
        schemas.stream().anyMatch(Optional::isPresent),
        "configured outputSchema must route through the schema-bearing chatStream so the provider's"
            + " native structured-output channel sees the schema on every turn — the matchmaking"
            + " bug regression");
    assertEquals(Optional.of(schema), schemas.getFirst());
    assertFalse(schemas.contains(Optional.empty()));
  }

  @Test
  void sessionWithoutOutputSchemaUsesTheUnconstrainedDispatch() throws Exception {
    var model = streamingJson("{\"field\":\"untyped\"}");
    try (var session =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(model)
                .withSessionId(SID)
                .withClock(CLOCK)
                .build())) {
      var terminal = session.runBlocking(UserMessage.text("go"));
      var success = assertInstanceOf(ResultMessage.Success.class, terminal);
      assertEquals("{\"field\":\"untyped\"}", success.result());
    }
    assertTrue(
        model.outputSchemas().contains(Optional.empty()),
        "no outputSchema configured: the loop dispatches the unconstrained chatStream so the model"
            + " is free to produce arbitrary text");
    assertFalse(model.outputSchemas().stream().anyMatch(Optional::isPresent));
  }
}
