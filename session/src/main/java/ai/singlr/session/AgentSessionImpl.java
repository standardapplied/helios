/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import ai.singlr.core.common.Strings;
import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.core.runtime.SessionContext;
import ai.singlr.session.ask.AskUserQuestionRequest;
import ai.singlr.session.ask.AskUserQuestionResponse;
import ai.singlr.session.ask.AskUserQuestionTool;
import ai.singlr.session.ask.QuestionGateway;
import ai.singlr.session.execution.ExecutionProvider;
import ai.singlr.session.execution.SessionStartOutcome;
import ai.singlr.session.hooks.DefaultHookContext;
import ai.singlr.session.hooks.Hook;
import ai.singlr.session.hooks.HookContext;
import ai.singlr.session.hooks.HookRegistry;
import ai.singlr.session.loop.AgentLoop;
import ai.singlr.session.loop.SessionState;
import ai.singlr.session.loop.StopClassifier;
import ai.singlr.session.loop.ToolDispatch;
import ai.singlr.session.loop.TurnRunner;
import ai.singlr.session.memory.MemoryReadTool;
import ai.singlr.session.memory.MemoryWriteTool;
import ai.singlr.session.permissions.DefaultPermissionEvaluator;
import ai.singlr.session.tools.ToolBinding;
import ai.singlr.session.tools.ToolRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Concrete {@link AgentSession} implementation.
 *
 * <p>One instance per session. Builds the loop substrate ({@link SessionState}, {@link
 * SteeringQueue}, {@link HookRegistry}, {@link ToolDispatch}, {@link TurnRunner}, {@link
 * StopClassifier}, {@link AgentLoop}) in the constructor; defers starting the agent-loop virtual
 * thread until the first {@link #send(UserMessage)} or {@link #interrupt(String)} call so
 * subscribers attached between construction and first send observe every event.
 *
 * <h2>Event delivery</h2>
 *
 * Events flow through a {@link SubmissionPublisher} sized at the JDK default 256-item buffer per
 * subscriber. Subscriber delivery uses a per-session virtual-thread executor; a slow subscriber
 * back-pressures the agent loop via {@code submit} rather than silently dropping events.
 *
 * <h2>Thread-safety</h2>
 *
 * Thread-safe. Producer threads (HTTP, UI) call {@link #send}/{@link #interrupt}/{@link #close}
 * concurrently; the agent loop runs on a dedicated virtual thread. Atomic flags coordinate
 * lifecycle. The loop is the only writer to {@link SessionState}'s mutable fields aside from {@code
 * close()}'s pre-start terminal write, which is guarded by the same compare-and-set as the loop
 * launch — at most one of the two paths executes.
 */
public final class AgentSessionImpl implements AgentSession {

  private static final Logger LOGGER = Logger.getLogger(AgentSessionImpl.class.getName());
  private static final int PUBLISHER_BUFFER = 256;

  private final String sessionId;
  private final SessionState state;
  private final SessionContext sessionContext;
  private final ExecutionProvider executionProvider;
  private final boolean providerAccepted;
  private final SteeringQueue steeringQueue;
  private final SessionLimits limits;
  private final SubmissionPublisher<QueryEvent> publisher;
  private final ExecutorService publisherExecutor;
  private final AgentLoop loop;
  private final CompletableFuture<ResultMessage> resultFuture = new CompletableFuture<>();
  private final AtomicBoolean started = new AtomicBoolean(false);
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final ConcurrentHashMap<String, CompletableFuture<AskUserQuestionResponse>>
      pendingQuestions = new ConcurrentHashMap<>();
  private final Clock clock;

  /**
   * Build a session from a composition record.
   *
   * @param options the configuration bundle; non-null
   * @throws NullPointerException if {@code options} is null
   */
  public AgentSessionImpl(SessionOptions options) {
    Objects.requireNonNull(options, "options must not be null");
    this.sessionId = options.sessionId();
    this.limits = options.limits();
    this.clock = options.clock();
    this.executionProvider = options.executionProvider();
    var concurrency = options.concurrency();
    var cancellation = new CancellationToken();
    this.state = new SessionState(sessionId, cancellation, clock);
    options
        .systemPrompt()
        .ifPresent(prompt -> this.state.appendMessage(ai.singlr.core.model.Message.system(prompt)));
    this.sessionContext = new SessionContext(sessionId, cancellation, clock);
    this.steeringQueue = new SteeringQueue(concurrency.maxQueuedUserMessages());
    this.publisherExecutor = Executors.newVirtualThreadPerTaskExecutor();
    this.publisher = new SubmissionPublisher<>(publisherExecutor, PUBLISHER_BUFFER);
    var sessionGateway = new SessionQuestionGateway();
    var combinedTools = withBuiltins(options.tools(), options, sessionGateway);
    var toolDispatch = new ToolDispatch(sessionContext, combinedTools, concurrency);
    this.providerAccepted = invokeOnSessionStart();
    var combinedHooks = new ArrayList<Hook>(options.hooks().size() + 1);
    options
        .permission()
        .ifPresent(
            p ->
                combinedHooks.add(
                    DefaultPermissionEvaluator.newBuilder(p, combinedTools)
                        .withQuestionGateway(sessionGateway)
                        .build()));
    combinedHooks.addAll(options.hooks());
    var hookRegistry = new HookRegistry(combinedHooks);
    var model = options.model();
    Function<SessionState, HookContext> contextFactory =
        s -> new DefaultHookContext(s.sessionId(), s.currentTurnIndex(), s.cancellation(), model);
    var turnRunner =
        new TurnRunner(
            model,
            hookRegistry,
            toolDispatch,
            steeringQueue,
            publisher::submit,
            contextFactory,
            clock,
            options.costCalculator());
    this.loop =
        new AgentLoop(
            turnRunner,
            new StopClassifier(),
            hookRegistry,
            toolDispatch,
            steeringQueue,
            publisher::submit,
            contextFactory,
            clock,
            options.tokenCounter(),
            options.contextCompactor());
  }

  /**
   * Fire {@code executionProvider.onSessionStart(sessionContext)} and react to its outcome. When
   * the provider returns {@link SessionStartOutcome.Refuse}, settle the result future immediately
   * with {@link ResultMessage.ErrorProviderUnavailable} and mark the started flag so subsequent
   * {@link #send} / {@link #interrupt} calls observe a terminal session.
   *
   * @return {@code true} when the provider accepted (so {@link #closeRuntime} must fire {@code
   *     onSessionEnd}); {@code false} when the session was refused
   */
  private boolean invokeOnSessionStart() {
    SessionStartOutcome outcome;
    try {
      outcome = executionProvider.onSessionStart(sessionContext);
    } catch (RuntimeException e) {
      markRefused(
          "onSessionStart threw "
              + e.getClass().getSimpleName()
              + ": "
              + (e.getMessage() == null ? "(no message)" : e.getMessage()));
      return false;
    }
    Objects.requireNonNull(outcome, "onSessionStart returned null");
    if (outcome instanceof SessionStartOutcome.Refuse refuse) {
      markRefused(refuse.reason());
      return false;
    }
    return true;
  }

  private void markRefused(String reason) {
    var refusal =
        new ResultMessage.ErrorProviderUnavailable(
            sessionId,
            executionProvider.getClass().getSimpleName(),
            reason,
            state.usage(),
            state.cost(),
            state.elapsed());
    state.setTerminal(refusal);
    resultFuture.complete(refusal);
  }

  @Override
  public void send(UserMessage message) {
    Objects.requireNonNull(message, "message must not be null");
    if (closed.get()) {
      throw new IllegalStateException("session is closed");
    }
    if (state.isTerminal()) {
      throw new IllegalStateException("session is terminal");
    }
    if (!steeringQueue.offer(message)) {
      throw new IllegalStateException(
          "steering queue full at capacity " + steeringQueue.capacity());
    }
    startIfNeeded();
  }

  @Override
  public void interrupt(String reason) {
    Objects.requireNonNull(reason, "reason must not be null");
    if (Strings.isBlank(reason)) {
      throw new IllegalArgumentException("reason must not be blank");
    }
    if (closed.get()) {
      throw new IllegalStateException("session is closed");
    }
    if (state.isTerminal()) {
      throw new IllegalStateException("session is terminal");
    }
    var synthetic = UserMessage.text("[interrupted by user: " + reason + "]");
    if (!steeringQueue.offer(synthetic)) {
      throw new IllegalStateException(
          "steering queue full at capacity "
              + steeringQueue.capacity()
              + " — cannot enqueue interrupt");
    }
    startIfNeeded();
  }

  @Override
  public Flow.Publisher<QueryEvent> events() {
    return publisher;
  }

  @Override
  public CompletableFuture<ResultMessage> result() {
    return resultFuture;
  }

  @Override
  public String sessionId() {
    return sessionId;
  }

  @Override
  public long currentTurnIndex() {
    return state.currentTurnIndex();
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    state.cancellation().cancel("session closed");
    cancelPendingQuestions();
    // If the loop has never started, complete the future ourselves so result().get() doesn't
    // hang. If the loop is running, it will observe the cancellation on its next iteration and
    // complete the future via runLoop's finally block — we leave it alone here.
    if (started.compareAndSet(false, true)) {
      // Pre-start close: write a Cancelled terminal unless one is already recorded (a refused
      // session set ErrorProviderUnavailable in the constructor). state.setTerminal and
      // resultFuture.complete are both first-wins so re-attempting is a no-op.
      if (!state.isTerminal()) {
        var preStartResult =
            new ResultMessage.Cancelled(
                sessionId, "session closed", state.usage(), state.cost(), state.elapsed());
        state.setTerminal(preStartResult);
        resultFuture.complete(preStartResult);
      }
      closeRuntime();
    }
  }

  /**
   * Shut down the publisher and its per-session executor with a bounded grace period. Called from
   * exactly one of two mutually-exclusive paths — {@link #close()}'s pre-start branch, or {@link
   * #runLoop()}'s {@code finally} — and never both, because the {@code started} CAS gates entry.
   *
   * <p>The 5-second grace mirrors the model-close pattern in CLAUDE.md: enough time for a
   * cooperative subscriber to drain its {@code onComplete} task, short enough that a wedged
   * subscriber does not pin session shutdown.
   */
  private void closeRuntime() {
    if (providerAccepted) {
      try {
        executionProvider.onSessionEnd(sessionContext);
      } catch (RuntimeException e) {
        LOGGER.log(Level.WARNING, "onSessionEnd threw — continuing shutdown", e);
      }
    }
    publisher.close();
    publisherExecutor.shutdown();
    try {
      if (!publisherExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
        publisherExecutor.shutdownNow();
      }
    } catch (InterruptedException e) {
      publisherExecutor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  /** Package-private accessor for tests that need to assert executor shutdown. */
  ExecutorService publisherExecutorForTests() {
    return publisherExecutor;
  }

  @Override
  public void answer(String questionId, AskUserQuestionResponse response) {
    Objects.requireNonNull(questionId, "questionId must not be null");
    if (Strings.isBlank(questionId)) {
      throw new IllegalArgumentException("questionId must not be blank");
    }
    Objects.requireNonNull(response, "response must not be null");
    if (!questionId.equals(response.questionId())) {
      throw new IllegalArgumentException(
          "response.questionId() '"
              + response.questionId()
              + "' does not match argument '"
              + questionId
              + "'");
    }
    if (closed.get()) {
      throw new IllegalStateException("session is closed");
    }
    var pending = pendingQuestions.remove(questionId);
    if (pending == null) {
      throw new IllegalArgumentException(
          "no pending question with id '" + questionId + "' — already answered or unknown");
    }
    pending.complete(response);
  }

  private ToolRegistry withBuiltins(
      ToolRegistry userTools, SessionOptions options, QuestionGateway gateway) {
    var combined = new ArrayList<ToolBinding>(userTools.bindings().size() + 3);
    combined.addAll(userTools.bindings());
    combined.add(AskUserQuestionTool.binding(gateway));
    options
        .memoryBackend()
        .ifPresent(
            b -> {
              combined.add(MemoryReadTool.binding(b));
              combined.add(MemoryWriteTool.binding(b));
            });
    return new ToolRegistry(combined);
  }

  private void cancelPendingQuestions() {
    // Snapshot to avoid concurrent-mutation surprises while completing.
    for (var entry : new ArrayList<>(pendingQuestions.entrySet())) {
      var future = pendingQuestions.remove(entry.getKey());
      if (future != null) {
        future.completeExceptionally(new CancellationException("session cancelled"));
      }
    }
  }

  /**
   * Session-internal gateway that emits the {@code QuestionAsked} event and blocks on a future.
   *
   * <p>Cancellation is wired via {@link CancellationToken#onCancel(Runnable)} — when the session
   * cancels, the registered callback completes the pending future with a {@link
   * CancellationException}, waking {@code future.get()} immediately. No polling.
   *
   * <p>{@link CompletableFuture#get()} special-cases {@code CancellationException}-shaped results
   * and re-throws them directly rather than wrapping in {@code ExecutionException}, so the only
   * checked throwables we have to propagate are {@link InterruptedException} and {@link
   * CancellationException}. A defensive {@code ExecutionException} catch covers the theoretical
   * case where some future caller completes the future with a non-cancellation throwable; we
   * re-wrap as cancellation so the agent loop's tool dispatcher sees a coherent failure.
   */
  private final class SessionQuestionGateway implements QuestionGateway {

    @Override
    public AskUserQuestionResponse ask(AskUserQuestionRequest request)
        throws InterruptedException, CancellationException {
      Objects.requireNonNull(request, "request must not be null");
      var future = new CompletableFuture<AskUserQuestionResponse>();
      pendingQuestions.put(request.questionId(), future);
      state
          .cancellation()
          .onCancel(
              () ->
                  future.completeExceptionally(
                      new CancellationException(
                          state.cancellation().reason().orElse("session cancelled"))));
      try {
        publisher.submit(
            new QueryEvent.QuestionAsked(
                sessionId, state.currentTurnIndex(), Instant.now(clock), request));
        return future.get();
      } catch (ExecutionException e) {
        var cause = e.getCause();
        throw new CancellationException(
            "question "
                + request.questionId()
                + " failed: "
                + (cause == null ? "no cause" : cause.getMessage()));
      } finally {
        pendingQuestions.remove(request.questionId());
      }
    }
  }

  private void startIfNeeded() {
    if (started.compareAndSet(false, true)) {
      Thread.ofVirtual().name("helios-agent-loop-" + sessionId).start(this::runLoop);
    }
  }

  private void runLoop() {
    try {
      resultFuture.complete(loop.run(state, limits));
    } catch (Throwable t) {
      // AgentLoop.run catches Exception and returns a terminal; an Error subtype escaping (OOM,
      // StackOverflow, LinkageError, AssertionError) must still settle the future, otherwise every
      // caller blocked on result().join() hangs forever. Re-throw preserves AgentLoop's intent that
      // unrecoverable Errors take down the host thread.
      resultFuture.completeExceptionally(t);
      throw t;
    } finally {
      closeRuntime();
    }
  }
}
