/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.loop;

import ai.singlr.core.common.CostCalculator;
import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.ModelChunk;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.Response.Usage;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.model.TransientStreamException;
import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.schema.StructuredOutputParseException;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.session.QueryEvent;
import ai.singlr.session.ResultMessage;
import ai.singlr.session.SerializedError;
import ai.singlr.session.SessionLimits;
import ai.singlr.session.SteeringQueue;
import ai.singlr.session.StopReason;
import ai.singlr.session.UserMessage;
import ai.singlr.session.hooks.HookContext;
import ai.singlr.session.hooks.HookDecision;
import ai.singlr.session.hooks.HookOutcome;
import ai.singlr.session.hooks.HookRegistry;
import ai.singlr.session.tools.ToolBinding;
import ai.singlr.session.tools.ToolVisibilityContext;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Drives one model turn end-to-end. Fires hooks at PreModelTurn, PostModelTurn, PreToolUse (per
 * call), PostToolUse (per call), and OnStreamEvent (per emission). Translates {@link ModelChunk}s
 * into {@link QueryEvent}s on the event sink, dispatches tool calls via {@link ToolDispatch},
 * appends assistant + tool messages to {@link SessionState} history, and returns a {@link
 * TurnOutcome} the agent loop hands to {@link StopClassifier}.
 *
 * <h2>Hook outcomes</h2>
 *
 * <ul>
 *   <li><b>PreModelTurn</b>: {@code Continue} proceeds; {@code Inject} queues a synthetic user
 *       message and skips the model call (turn ends with finish reason {@code TOOL_CALLS} so the
 *       loop continues); {@code Stop} terminates the session with the given result.
 *   <li><b>PostModelTurn</b>: {@code Continue} proceeds; {@code Inject} queues a synthetic user
 *       message (loop continues); {@code Stop} terminates.
 *   <li><b>PreToolUse</b>: {@code Continue} dispatches; {@code MutateInput} emits {@link
 *       QueryEvent.ToolMutated} and dispatches with the replacement args; {@code Block} emits
 *       {@link QueryEvent.ToolBlocked} and substitutes a synthetic failure {@link ToolResult};
 *       {@code Inject} queues a synthetic user message and substitutes a synthetic failure {@code
 *       ToolResult}; {@code Stop} terminates.
 *   <li><b>PostToolUse</b>: {@code Continue} proceeds; {@code MutateInput} rewrites the tool result
 *       content (key {@code "output"}); {@code Inject} queues a synthetic user message; {@code
 *       Stop} terminates.
 * </ul>
 *
 * <p>{@link QueryEvent.HookFired} fires for every non-{@code Continue} outcome.
 *
 * <h2>Thread-safety</h2>
 *
 * One instance is safe to share across many sessions: it has no mutable state of its own. Each
 * {@link #runTurn(SessionState, ai.singlr.session.SessionLimits)} invocation creates its own
 * subscriber state.
 */
public final class TurnRunner {

  private static final Logger LOGGER = Logger.getLogger(TurnRunner.class.getName());

  private final Model model;
  private final HookRegistry hooks;
  private final ToolDispatch toolDispatch;
  private final SteeringQueue steeringQueue;
  private final Function<SessionState, HookContext> hookContextFactory;
  private final Clock clock;
  private final EventEmitter emitter;
  private final CostCalculator costCalculator;
  private final OutputSchema<?> outputSchema;
  private final ScheduledExecutorService scheduler;

  /**
   * Lazily-initialised process-wide daemon scheduler used by the 9-arg convenience constructor.
   * Production callers ({@link ai.singlr.session.AgentSessionImpl}) construct a session-scoped
   * scheduler and pass it via the 10-arg constructor so resource lifetime is bounded; this default
   * exists only so test fixtures don't have to wire one. Daemon threads never block JVM exit.
   */
  private static volatile ScheduledExecutorService DEFAULT_SCHEDULER;

  private static synchronized ScheduledExecutorService sharedDefaultScheduler() {
    if (DEFAULT_SCHEDULER == null) {
      DEFAULT_SCHEDULER =
          Executors.newSingleThreadScheduledExecutor(
              r -> {
                var t = new Thread(r, "helios-turnrunner-default-scheduler");
                t.setDaemon(true);
                return t;
              });
    }
    return DEFAULT_SCHEDULER;
  }

  /**
   * Build a turn runner.
   *
   * @param model the model providing {@link Model#chatStream(List, List,
   *     ai.singlr.core.runtime.CancellationToken)}; non-null
   * @param hooks priority-sorted hook registry; non-null
   * @param toolDispatch dispatcher for tool calls the model emits; non-null
   * @param steeringQueue per-session inbox into which Inject-outcome hooks enqueue synthetic
   *     messages; non-null
   * @param eventSink consumer for {@link QueryEvent}s emitted during the turn; non-null
   * @param hookContextFactory builds a per-fire {@link HookContext} from the session state;
   *     non-null
   * @param clock clock supplying event timestamps; non-null
   * @param costCalculator converts per-turn {@link Usage} into a {@link
   *     ai.singlr.core.common.CostEstimate}; non-null. Use {@link CostCalculator#ZERO} to disable
   *     cost tracking
   * @param outputSchema the schema the model's text output must conform to, or {@code null} when
   *     the session has no structured-output constraint. When non-null, the loop dispatches {@link
   *     Model#chatStream(List, List, OutputSchema, ai.singlr.core.runtime.CancellationToken)} on
   *     every turn so the schema rides the provider's native channel (Gemini {@code
   *     response_format.schema}, OpenAI {@code text.format=json_schema}, Anthropic {@code
   *     system_instruction} text). The schema is dormant on tool-calling turns (tool arguments
   *     validate against the tool's own schema) and activates on text-output turns. When {@code
   *     null}, the loop dispatches {@link Model#chatStream(List, List,
   *     ai.singlr.core.runtime.CancellationToken)} and the model is free to produce arbitrary text
   * @throws NullPointerException if any non-{@code outputSchema} argument is null
   */
  public TurnRunner(
      Model model,
      HookRegistry hooks,
      ToolDispatch toolDispatch,
      SteeringQueue steeringQueue,
      Consumer<QueryEvent> eventSink,
      Function<SessionState, HookContext> hookContextFactory,
      Clock clock,
      CostCalculator costCalculator,
      OutputSchema<?> outputSchema) {
    this(
        model,
        hooks,
        toolDispatch,
        steeringQueue,
        eventSink,
        hookContextFactory,
        clock,
        costCalculator,
        outputSchema,
        sharedDefaultScheduler());
  }

  /**
   * Same as {@link #TurnRunner(Model, HookRegistry, ToolDispatch, SteeringQueue, Consumer,
   * Function, Clock, CostCalculator, OutputSchema)} but supplies an explicit scheduler. Production
   * callers ({@link ai.singlr.session.AgentSessionImpl}) pass a session-scoped scheduler that is
   * shut down with the session; test fixtures use the convenience 9-arg overload that defaults to a
   * process-wide daemon scheduler.
   *
   * @param model the model providing {@link Model#chatStream(List, List,
   *     ai.singlr.core.runtime.CancellationToken)}; non-null
   * @param hooks priority-sorted hook registry; non-null
   * @param toolDispatch dispatcher for tool calls the model emits; non-null
   * @param steeringQueue per-session inbox into which Inject-outcome hooks enqueue synthetic
   *     messages; non-null
   * @param eventSink consumer for {@link QueryEvent}s emitted during the turn; non-null
   * @param hookContextFactory builds a per-fire {@link HookContext} from the session state;
   *     non-null
   * @param clock clock supplying event timestamps; non-null
   * @param costCalculator converts per-turn {@link Usage} into a {@link
   *     ai.singlr.core.common.CostEstimate}; non-null
   * @param outputSchema the schema the model's text output must conform to, or {@code null} when
   *     the session has no structured-output constraint
   * @param scheduler shared per-session scheduler used by {@link TurnSubscriber} to enforce the
   *     per-chunk {@code streamIdleTimeout}; non-null
   */
  public TurnRunner(
      Model model,
      HookRegistry hooks,
      ToolDispatch toolDispatch,
      SteeringQueue steeringQueue,
      Consumer<QueryEvent> eventSink,
      Function<SessionState, HookContext> hookContextFactory,
      Clock clock,
      CostCalculator costCalculator,
      OutputSchema<?> outputSchema,
      ScheduledExecutorService scheduler) {
    this.model = Objects.requireNonNull(model, "model must not be null");
    this.hooks = Objects.requireNonNull(hooks, "hooks must not be null");
    this.toolDispatch = Objects.requireNonNull(toolDispatch, "toolDispatch must not be null");
    this.steeringQueue = Objects.requireNonNull(steeringQueue, "steeringQueue must not be null");
    this.hookContextFactory =
        Objects.requireNonNull(hookContextFactory, "hookContextFactory must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.costCalculator = Objects.requireNonNull(costCalculator, "costCalculator must not be null");
    this.outputSchema = outputSchema;
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
    this.emitter = new EventEmitter(eventSink, hooks, hookContextFactory, clock);
  }

  /**
   * Run one turn against the model and return its outcome.
   *
   * @param state the session state; non-null
   * @param limits the session limits; non-null
   * @return the outcome of the turn
   * @throws NullPointerException if {@code state} or {@code limits} is null
   */
  public TurnOutcome runTurn(SessionState state, SessionLimits limits) {
    Objects.requireNonNull(state, "state must not be null");
    Objects.requireNonNull(limits, "limits must not be null");

    // PreModelTurn
    var preDecision = hooks.firePreModelTurn(state.historySnapshot(), ctx(state));
    var preResolved = handleTurnLevel(state, preDecision, TurnPhase.PRE_MODEL_TURN);
    if (preResolved == TurnLevelDecision.TERMINATE) {
      return turnEnded(state, finalOutcomeAfterTerminate(state));
    }
    if (preResolved == TurnLevelDecision.SKIP_MODEL) {
      var skipped = new TurnOutcome(FinishReason.TOOL_CALLS, "", Usage.of(0, 0));
      return turnEnded(state, skipped);
    }

    var visibilityCtx = new ToolVisibilityContext(state.sessionId(), state.currentTurnIndex());
    var visibleTools =
        toolDispatch.registry().visible(visibilityCtx).stream().map(ToolBinding::tool).toList();

    var streamAttempt = runStreamWithRetry(state, limits, visibleTools);
    var subscriber = streamAttempt.subscriber();
    var attemptsMade = streamAttempt.attemptsMade();

    var schemaRecovery = trySelfCorrectSchema(state, subscriber);
    if (schemaRecovery != null) {
      return schemaRecovery;
    }

    var streamOutcome = subscriber.toOutcome(attemptsMade);
    var toolCalls = subscriber.toolCalls();

    if (!toolCalls.isEmpty()) {
      state.appendMessage(
          Message.assistant(streamOutcome.assistantContent(), toolCalls, streamOutcome.metadata()));
      var stopDuringDispatch = dispatchToolCalls(state, toolCalls, limits);
      if (stopDuringDispatch) {
        return turnEnded(state, finalOutcomeAfterTerminate(state));
      }
    } else if (streamOutcome.finishReason() != FinishReason.ERROR
        && !streamOutcome.assistantContent().isEmpty()) {
      state.appendMessage(
          Message.assistant(streamOutcome.assistantContent(), List.of(), streamOutcome.metadata()));
    }
    accumulateUsageAndCost(state, streamOutcome.usage());

    // PostModelTurn
    var response =
        Response.newBuilder()
            .withContent(streamOutcome.assistantContent())
            .withFinishReason(streamOutcome.finishReason())
            .withUsage(streamOutcome.usage())
            .withToolCalls(toolCalls)
            .build();
    var postDecision = hooks.firePostModelTurn(response, ctx(state));
    var postResolved = handleTurnLevel(state, postDecision, TurnPhase.POST_MODEL_TURN);
    if (postResolved == TurnLevelDecision.TERMINATE) {
      return turnEnded(state, finalOutcomeAfterTerminate(state));
    }

    var finalOutcome =
        toolCalls.isEmpty()
            ? streamOutcome
            : new TurnOutcome(
                FinishReason.TOOL_CALLS, streamOutcome.assistantContent(), streamOutcome.usage());
    return turnEnded(state, finalOutcome);
  }

  /**
   * Accumulate {@code usage} into the session totals and apply the configured {@link
   * CostCalculator} against {@link Model#id()} to update accumulated cost. Exposed to the {@link
   * AgentLoop} so {@code ContextCompactor}-reported {@code Usage} (e.g. summary call spend) flows
   * through the same cost gate as a normal model turn.
   *
   * @param state the session state; non-null
   * @param usage the usage to accumulate; non-null. {@link Usage#of(int, int) Usage.of(0, 0)} is a
   *     legal no-op
   */
  void accumulateUsageAndCost(SessionState state, Usage usage) {
    Objects.requireNonNull(state, "state must not be null");
    Objects.requireNonNull(usage, "usage must not be null");
    state.accumulateUsage(usage);
    state.accumulateCost(costCalculator.cost(model.id(), usage));
  }

  private TurnOutcome finalOutcomeAfterTerminate(SessionState state) {
    return new TurnOutcome(FinishReason.STOP, "", state.usage());
  }

  /**
   * Schema-mismatch self-correction. When the model emitted parseable JSON that didn't match the
   * session's configured {@link OutputSchema}, the provider raises {@link
   * StructuredOutputParseException} (the only recoverable parse signal — syntactic JSON errors and
   * provider IO errors still terminate via {@link FinishReason#ERROR}).
   *
   * <p>Recovery: append the model's wrong attempt to history as an assistant message so the model
   * sees its own response through conversation context on the retry, then enqueue {@link
   * StructuredOutputParseException#correctionMessage()} as a synthetic user turn — that's the
   * field-level diff only, no rawContent echo (per the exception's class-level note on retry cost).
   * Return a {@link TurnOutcome} with {@link FinishReason#TOOL_CALLS}; the TOOL_CALLS sentinel
   * mirrors the {@code SKIP_MODEL} inject-hook path and routes back through the iteration boundary
   * regardless of {@link StopClassifier} state. The overall retry count is bounded by {@link
   * ai.singlr.session.SessionLimits#maxTurns()} — no dedicated parse-retry ceiling.
   *
   * <p>Returns {@code null} when the subscriber's error is not a {@link
   * StructuredOutputParseException} (or there is no error, or the steering queue rejected the
   * correction message), so the caller falls through to its regular turn-finalisation path and lets
   * the underlying error surface as {@link ResultMessage.ErrorDuringExecution}.
   */
  private TurnOutcome trySelfCorrectSchema(SessionState state, TurnSubscriber subscriber) {
    var err = subscriber.error();
    if (!(err instanceof StructuredOutputParseException parseFailure)) {
      return null;
    }
    var wrongAttempt = parseFailure.rawContent();
    if (wrongAttempt == null || wrongAttempt.isEmpty()) {
      // Real streaming providers may surface text deltas before the error fires; the subscriber
      // has accumulated them even when the exception itself didn't carry rawContent.
      wrongAttempt = subscriber.accumulatedContent();
    }
    if (!wrongAttempt.isEmpty()) {
      state.appendMessage(Message.assistant(wrongAttempt, List.of(), Map.of()));
    }
    if (!steeringQueue.offer(UserMessage.text(parseFailure.correctionMessage()))) {
      LOGGER.log(
          Level.WARNING,
          "schema-correction message was dropped: steering queue full; the loop will terminate"
              + " on the underlying parse error");
      return null;
    }
    return turnEnded(state, new TurnOutcome(FinishReason.TOOL_CALLS, "", state.usage()));
  }

  private TurnOutcome turnEnded(SessionState state, TurnOutcome outcome) {
    emitter.emit(
        state,
        new QueryEvent.TurnEnded(
            state.sessionId(),
            state.currentTurnIndex(),
            clock.instant(),
            mapFinishReasonToStopReason(outcome.finishReason())));
    return outcome;
  }

  /**
   * Outcome of the inner stream-retry loop: the final subscriber (whose recorded error, if any, the
   * caller inspects) and the total attempts made.
   */
  private record StreamAttemptResult(TurnSubscriber subscriber, int attemptsMade) {}

  /**
   * Run {@code model.chatStream(...)} with bounded retry on {@link TransientStreamException},
   * governed by {@link SessionLimits#streamRetryPolicy()}. Each failed attempt emits a {@link
   * QueryEvent.TurnRetried} carrying the next back-off delay; the back-off sleep honours the
   * session's {@link CancellationToken} so wall-clock / explicit-cancel terminations abort cleanly
   * without burning the full delay. Returns the final subscriber and the total attempt count; the
   * caller maps a still-failing transient subscriber error to {@link
   * ResultMessage.ErrorTransientStream} via {@link StopClassifier}.
   *
   * <p>Non-transient throwables ({@link StructuredOutputParseException}, {@code
   * java.util.concurrent.TimeoutException} from the stream-idle watchdog, or any other unchecked
   * exception escaping the subscribe call) end the loop immediately — only the type guard on {@code
   * TransientStreamException} triggers a retry.
   */
  private StreamAttemptResult runStreamWithRetry(
      SessionState state, SessionLimits limits, List<Tool> visibleTools) {
    var policy = limits.streamRetryPolicy();
    TurnSubscriber subscriber = null;
    int attempt = 0;
    while (true) {
      attempt++;
      subscriber = new TurnSubscriber(state, emitter, clock, scheduler, limits.streamIdleTimeout());
      try {
        var publisher =
            outputSchema != null
                ? model.chatStream(
                    state.historySnapshot(), visibleTools, outputSchema, state.cancellation())
                : model.chatStream(state.historySnapshot(), visibleTools, state.cancellation());
        publisher.subscribe(subscriber);
      } catch (Throwable t) {
        subscriber.onError(t);
      }
      subscriber.awaitDone(state.cancellation());

      var err = subscriber.error();
      if (!(err instanceof TransientStreamException transientErr)) {
        return new StreamAttemptResult(subscriber, attempt);
      }
      if (attempt >= policy.maxAttempts() || state.cancellation().isCancelled()) {
        return new StreamAttemptResult(subscriber, attempt);
      }
      var backoff = policy.nextDelay(attempt);
      emitter.emit(
          state,
          new QueryEvent.TurnRetried(
              state.sessionId(),
              state.currentTurnIndex(),
              clock.instant(),
              attempt,
              backoff,
              transientErr.providerName(),
              SerializedError.of(transientErr)));
      if (!sleepHonouringCancellation(backoff, state.cancellation())) {
        return new StreamAttemptResult(subscriber, attempt);
      }
    }
  }

  /**
   * Sleep for {@code delay}, returning early when {@code cancellation} fires. Returns {@code true}
   * when the full delay elapsed (or {@code delay} was zero); {@code false} when the sleep was cut
   * short by cancellation or thread interruption.
   *
   * <p>Implementation: a one-shot {@link CountDownLatch} the cancellation callback counts down;
   * {@code latch.await(timeout)} returns {@code true} when the latch counted down (cancelled) and
   * {@code false} when the timeout elapsed (slept the full duration). The callback registration is
   * removed in {@code finally} so a long-lived session token does not accumulate dead callbacks
   * across many retries.
   *
   * <p>{@code delay} is contractually non-null and non-negative — callers pass {@link
   * StreamRetryPolicy#nextDelay(int)} which is itself bounded by {@link
   * ai.singlr.core.fault.Backoff}'s validation. Zero short-circuits the latch path; the caller's
   * subsequent retry attempt re-checks cancellation before issuing the next request.
   */
  private static boolean sleepHonouringCancellation(Duration delay, CancellationToken token) {
    if (token.isCancelled()) {
      return false;
    }
    if (delay.isZero()) {
      return true;
    }
    var latch = new CountDownLatch(1);
    var registration = token.onCancel(latch::countDown);
    try {
      boolean cancelledDuringSleep = latch.await(delay.toMillis(), TimeUnit.MILLISECONDS);
      return !cancelledDuringSleep;
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      return false;
    } finally {
      registration.remove();
    }
  }

  /**
   * Decision returned by {@link #handleTurnLevel} for outcomes that affect whether to continue the
   * turn. Used by PreModelTurn / PostModelTurn / dispatch-time Stop handling.
   */
  private enum TurnLevelDecision {
    CONTINUE,
    SKIP_MODEL,
    TERMINATE
  }

  /**
   * Typed tag for {@link #handleTurnLevel}: the phase whose decision we're applying. Avoids a
   * stringly-typed {@code phaseName.equals("PreModelTurnHook")} comparison.
   */
  private enum TurnPhase {
    PRE_MODEL_TURN("PreModelTurnHook"),
    POST_MODEL_TURN("PostModelTurnHook");

    private final String phaseName;

    TurnPhase(String phaseName) {
      this.phaseName = phaseName;
    }

    String phaseName() {
      return phaseName;
    }
  }

  /**
   * Apply a turn-level hook outcome (PreModelTurn / PostModelTurn). Updates state for terminal /
   * injected outcomes and tells the caller whether to keep going.
   *
   * <p>{@code MutateInput} at PreModelTurn carries the "history" key (a {@code List<Message>}) and
   * rewrites the conversation history wholesale — the BYO-compactor-as-hook path. At PostModelTurn,
   * {@code MutateInput} is not meaningful and is treated as Continue.
   */
  private TurnLevelDecision handleTurnLevel(
      SessionState state, HookDecision decision, TurnPhase phase) {
    var hookName = decision.firingHookOptional().map(h -> h.name()).orElse(null);
    switch (decision.outcome()) {
      case HookOutcome.Continue ignored -> {
        return TurnLevelDecision.CONTINUE;
      }
      case HookOutcome.Stop s -> {
        emitter.emitHookFired(state, hookName, phase.phaseName(), "Stop");
        state.setTerminal(successFor(state, s.result()));
        return TurnLevelDecision.TERMINATE;
      }
      case HookOutcome.Inject inj -> {
        emitter.emitHookFired(state, hookName, phase.phaseName(), "Inject");
        offerOrLogDrop(inj.userMessage(), phase.phaseName(), hookName);
        return phase == TurnPhase.PRE_MODEL_TURN
            ? TurnLevelDecision.SKIP_MODEL
            : TurnLevelDecision.CONTINUE;
      }
      case HookOutcome.MutateInput m -> {
        if (phase == TurnPhase.PRE_MODEL_TURN) {
          var replacement = extractHistory(m.newInput());
          if (replacement != null) {
            state.replaceHistory(replacement);
            state.resetContextWarningFlag();
            emitter.emitHookFired(state, hookName, phase.phaseName(), "MutateInput");
          }
        }
        return TurnLevelDecision.CONTINUE;
      }
      case HookOutcome.Block b -> {
        // Block not meaningful at turn level; treated as Continue.
        return TurnLevelDecision.CONTINUE;
      }
    }
  }

  /**
   * Offer a hook-injected user message to the steering queue. Logs a WARNING if the queue rejects
   * it (e.g. full at capacity) — without this, hook authors believe their {@code Inject} took
   * effect but the loop never sees the message, producing impossible-to-debug cascading bugs.
   */
  private void offerOrLogDrop(String text, String phaseName, String hookName) {
    if (!steeringQueue.offer(UserMessage.text(text))) {
      LOGGER.log(
          Level.WARNING,
          "{0} hook ''{1}'' Inject was dropped: steering queue full; session continues without"
              + " the injected message",
          new Object[] {phaseName, hookName});
    }
  }

  /**
   * Pull a {@code List<Message>} out of a {@code MutateInput} payload under the "history" key.
   * Returns null when the key is absent, the value is the wrong type, or any element is not a
   * {@link Message} — the loop then treats the outcome as a no-op Continue rather than rewriting
   * the history with a malformed payload.
   */
  private static List<Message> extractHistory(Map<String, Object> newInput) {
    var raw = newInput.get("history");
    if (!(raw instanceof List<?> list)) {
      return null;
    }
    var copy = new ArrayList<Message>(list.size());
    for (var element : list) {
      if (!(element instanceof Message message)) {
        return null;
      }
      copy.add(message);
    }
    return List.copyOf(copy);
  }

  /**
   * Dispatch each tool call serially, firing PreToolUse and PostToolUse around each. Returns {@code
   * true} if a hook terminated the session mid-dispatch.
   */
  private boolean dispatchToolCalls(
      SessionState state, List<ToolCall> toolCalls, SessionLimits limits) {
    for (var call : toolCalls) {
      var preCall = call;
      var preDecision = hooks.firePreToolUse(call, ctx(state));
      var preHookName = preDecision.firingHookOptional().map(h -> h.name()).orElse(null);
      ToolResult result = null;
      switch (preDecision.outcome()) {
        case HookOutcome.Continue ignored -> {
          emitter.emit(
              state,
              new QueryEvent.ToolUse(
                  state.sessionId(), state.currentTurnIndex(), clock.instant(), call));
        }
        case HookOutcome.MutateInput m -> {
          emitter.emitHookFired(state, preHookName, "PreToolUseHook", "MutateInput");
          emitter.emit(
              state,
              new QueryEvent.ToolMutated(
                  state.sessionId(),
                  state.currentTurnIndex(),
                  clock.instant(),
                  call,
                  preHookName == null ? "PreToolUseHook" : preHookName,
                  call.arguments(),
                  m.newInput()));
          preCall = new ToolCall(call.id(), call.name(), m.newInput());
          emitter.emit(
              state,
              new QueryEvent.ToolUse(
                  state.sessionId(), state.currentTurnIndex(), clock.instant(), preCall));
        }
        case HookOutcome.Block b -> {
          emitter.emitHookFired(state, preHookName, "PreToolUseHook", "Block");
          emitter.emit(
              state,
              new QueryEvent.ToolBlocked(
                  state.sessionId(),
                  state.currentTurnIndex(),
                  clock.instant(),
                  call,
                  preHookName == null ? "PreToolUseHook" : preHookName,
                  b.reason()));
          result = ToolResult.failure("blocked by hook: " + b.reason());
        }
        case HookOutcome.Inject inj -> {
          emitter.emitHookFired(state, preHookName, "PreToolUseHook", "Inject");
          offerOrLogDrop(inj.userMessage(), "PreToolUseHook", preHookName);
          result = ToolResult.failure("tool skipped: hook injected user message");
        }
        case HookOutcome.Stop s -> {
          emitter.emitHookFired(state, preHookName, "PreToolUseHook", "Stop");
          state.setTerminal(successFor(state, s.result()));
          return true;
        }
      }

      if (result == null) {
        try {
          result =
              toolDispatch.dispatch(preCall, state.cancellation(), limits.toolTimeoutDefault());
        } catch (Throwable t) {
          var msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
          result = ToolResult.failure("tool dispatch failed: " + msg);
        }
      }
      emitter.emit(
          state,
          new QueryEvent.ToolResult(
              state.sessionId(), state.currentTurnIndex(), clock.instant(), preCall, result));

      // PostToolUse
      var postDecision = hooks.firePostToolUse(preCall, result, ctx(state));
      var postHookName = postDecision.firingHookOptional().map(h -> h.name()).orElse(null);
      switch (postDecision.outcome()) {
        case HookOutcome.Continue ignored -> {}
        case HookOutcome.MutateInput m -> {
          emitter.emitHookFired(state, postHookName, "PostToolUseHook", "MutateInput");
          var newOutput = stringField(m.newInput(), "output");
          if (newOutput != null) {
            result = ToolResult.success(newOutput);
            emitter.emit(
                state,
                new QueryEvent.ToolResult(
                    state.sessionId(), state.currentTurnIndex(), clock.instant(), preCall, result));
          }
        }
        case HookOutcome.Inject inj -> {
          emitter.emitHookFired(state, postHookName, "PostToolUseHook", "Inject");
          offerOrLogDrop(inj.userMessage(), "PostToolUseHook", postHookName);
        }
        case HookOutcome.Stop s -> {
          emitter.emitHookFired(state, postHookName, "PostToolUseHook", "Stop");
          appendToolResultWithAttachments(state, preCall, result);
          state.setTerminal(successFor(state, s.result()));
          return true;
        }
        case HookOutcome.Block b -> {
          // Block not meaningful at PostToolUse; treat as Continue.
        }
      }
      appendToolResultWithAttachments(state, preCall, result);
    }
    return false;
  }

  /**
   * Append a tool result to history, plus any multimodal attachments the tool returned. The text
   * goes on the standard tool-result message; attachments ride a follow-up user message so every
   * provider's existing {@code Message.user(text, inlineFiles)} plumbing handles wire encoding
   * uniformly — no per-provider {@code tool_result} multimodal wiring required.
   *
   * <p>The follow-up text names what was attached so the model has a textual handle in conversation
   * history even without re-reading the binary content on every subsequent turn. Empty-attachments
   * results emit only the tool-result message — the synthetic user message is skipped to keep the
   * conversation shape unchanged when no multimodal payload exists.
   */
  private static void appendToolResultWithAttachments(
      SessionState state, ToolCall preCall, ToolResult result) {
    state.appendMessage(Message.tool(preCall.id(), preCall.name(), result.output()));
    if (result.hasAttachments()) {
      var text =
          "[tool '"
              + preCall.name()
              + "' returned "
              + result.attachments().size()
              + " attachment(s) for inspection]";
      state.appendMessage(Message.user(text, result.attachments()));
    }
  }

  private static String stringField(Map<String, Object> map, String key) {
    var v = map.get(key);
    return v instanceof String s ? s : null;
  }

  private HookContext ctx(SessionState state) {
    return hookContextFactory.apply(state);
  }

  private ResultMessage successFor(SessionState state, String text) {
    return new ResultMessage.Success(
        state.sessionId(), text, state.usage(), state.cost(), state.elapsed());
  }

  private static StopReason mapFinishReasonToStopReason(FinishReason r) {
    return switch (r) {
      case STOP -> StopReason.END_TURN;
      case TOOL_CALLS -> StopReason.TOOL_USE;
      case LENGTH -> StopReason.MAX_TOKENS;
      case CONTENT_FILTER -> StopReason.REFUSAL;
      case ERROR -> StopReason.ERROR;
    };
  }
}
