/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.loop;

import ai.singlr.core.context.TokenCounter;
import ai.singlr.core.model.InlineFile;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Response;
import ai.singlr.session.CompactionResult;
import ai.singlr.session.ContextCompactor;
import ai.singlr.session.QueryEvent;
import ai.singlr.session.ResultMessage;
import ai.singlr.session.SerializedError;
import ai.singlr.session.SessionLimits;
import ai.singlr.session.SteeringQueue;
import ai.singlr.session.UserMessage;
import ai.singlr.session.hooks.CompactionPayload;
import ai.singlr.session.hooks.HookContext;
import ai.singlr.session.hooks.HookOutcome;
import ai.singlr.session.hooks.HookRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Top-level orchestrator that drives one open-ended session to a terminal {@link ResultMessage}.
 *
 * <p>Every iteration:
 *
 * <ol>
 *   <li>Drain the {@link SteeringQueue}. Each pending {@link UserMessage} fires {@link
 *       HookRegistry#fireOnUserMessage on-user-message hooks} — outcomes can drop the message
 *       ({@link HookOutcome.Block Block}), rewrite its text ({@link HookOutcome.MutateInput
 *       MutateInput}), or terminate the session ({@link HookOutcome.Stop Stop}). Surviving messages
 *       emit {@link QueryEvent.UserMessageReceived} and compose into a single user-role message
 *       appended to history.
 *   <li>If history is still empty (no message has ever been observed and the queue was empty),
 *       terminate with {@link ResultMessage.ErrorDuringExecution}.
 *   <li>Advance the turn counter and call {@link TurnRunner#runTurn(SessionState, SessionLimits)}.
 *   <li>If the turn left a terminal value on {@link SessionState} (hook-driven stop), respect it.
 *       Otherwise hand the outcome to {@link StopClassifier}; if it returns terminal, fire {@link
 *       HookRegistry#firePreStop pre-stop hooks} — {@link HookOutcome.Inject Inject} cancels the
 *       termination and queues a synthetic user message; {@link HookOutcome.Stop Stop} overrides
 *       the success text; {@link HookOutcome.Continue Continue} confirms the stop.
 *   <li>Otherwise iterate.
 * </ol>
 *
 * <p>Every emitted {@link QueryEvent} fires {@link HookRegistry#fireOnStreamEvent stream-event
 * hooks} for observe-only consumers.
 *
 * <h2>Thread-safety</h2>
 *
 * One AgentLoop instance per session. {@link #run(SessionState, SessionLimits)} runs on a single
 * virtual thread. Shared collaborators ({@link TurnRunner}, {@link StopClassifier}, {@link
 * HookRegistry}) are reusable across sessions; per-session collaborators ({@link SessionState},
 * {@link SteeringQueue}, {@link ToolDispatch}) are owned by the caller.
 */
public final class AgentLoop {

  private static final Logger LOGGER = Logger.getLogger(AgentLoop.class.getName());

  /**
   * Watermark fraction of {@link SessionLimits#maxContextTokens()} at which a {@link
   * QueryEvent.ContextWarning} fires for the first time. Sticky for the rest of the session until a
   * successful compaction clears the flag. 0.85 gives library users (and the compactor) ~15% of the
   * window to react before the model errors out.
   *
   * <p>The denominator is the configured {@code maxContextTokens} (or its model-aware resolution
   * when the caller left it at the auto sentinel). The numerator is the {@link
   * ai.singlr.core.context.TokenCounter}'s estimate of <em>total tokens across every message
   * currently in the conversation history</em> — cumulative across all turns since the session
   * started, never the size of a single turn. The watermark check runs both before and after every
   * turn so the trigger fires as soon as the cumulative history crosses the threshold.
   */
  static final double CONTEXT_WARNING_WATERMARK = 0.85;

  /**
   * Watermark fraction of {@link SessionLimits#maxContextTokens()} at which the loop invokes the
   * configured {@link ContextCompactor}. 0.95 keeps a 5% safety margin against the underlying
   * context window; below the warning watermark we'd compact too eagerly.
   *
   * <p>Same denominator and numerator as {@link #CONTEXT_WARNING_WATERMARK} — cumulative across the
   * full conversation history, not per-turn.
   */
  static final double CONTEXT_COMPACT_WATERMARK = 0.95;

  /**
   * Output-token headroom subtracted from {@link Model#contextWindow()} when {@link
   * SessionLimits#maxContextTokens()} is left in its sentinel "auto" state. Mirrors Claude Code's
   * {@code nAK = 20000} output reservation — leaves enough budget for the next response after a
   * full-context input, so the watermark check trips before the provider rejects an oversized
   * request. Library users that need a tighter or looser headroom set an explicit {@link
   * SessionLimits.Builder#withMaxContextTokens(long)} cap.
   */
  static final long AUTO_RESERVED_OUTPUT_TOKENS = 20_000L;

  /**
   * Backstop window used when both {@link SessionLimits#maxContextTokens()} is in its "auto"
   * sentinel AND the provider does not report a {@link Model#contextWindow()}. Matches the
   * pre-2.5.6 hardcoded default. Sized for Sonnet-class models — narrow enough not to fail a
   * conservatively-built session, wide enough that simple multi-turn workflows don't compact
   * prematurely.
   */
  static final long AUTO_FALLBACK_MAX_CONTEXT_TOKENS = 180_000L;

  private final TurnRunner turnRunner;
  private final StopClassifier classifier;
  private final HookRegistry hooks;
  private final ToolDispatch toolDispatch;
  private final SteeringQueue steeringQueue;
  private final Function<SessionState, HookContext> hookContextFactory;
  private final Clock clock;
  private final EventEmitter emitter;
  private final TokenCounter tokenCounter;
  private final ContextCompactor contextCompactor;

  /**
   * Build an agent loop.
   *
   * @param turnRunner the per-turn worker; non-null
   * @param classifier terminal-result classifier; non-null
   * @param hooks priority-sorted hook registry; non-null
   * @param toolDispatch tool dispatch surface; non-null
   * @param steeringQueue per-session user-message inbox; non-null
   * @param eventSink consumer for {@link QueryEvent}s emitted by the loop; non-null
   * @param hookContextFactory builds a per-fire {@link HookContext} from the session state;
   *     non-null
   * @param clock supplies event timestamps; non-null
   * @param tokenCounter estimator used after each model turn to detect when running history
   *     approaches the context window; non-null. {@link TokenCounter#charBased()} is the default
   * @param contextCompactor invoked when usage crosses {@link #CONTEXT_COMPACT_WATERMARK};
   *     non-null. Pass {@link ContextCompactor#disabled()} to opt out of automatic compaction
   * @throws NullPointerException if any argument is null
   */
  public AgentLoop(
      TurnRunner turnRunner,
      StopClassifier classifier,
      HookRegistry hooks,
      ToolDispatch toolDispatch,
      SteeringQueue steeringQueue,
      Consumer<QueryEvent> eventSink,
      Function<SessionState, HookContext> hookContextFactory,
      Clock clock,
      TokenCounter tokenCounter,
      ContextCompactor contextCompactor) {
    this.turnRunner = Objects.requireNonNull(turnRunner, "turnRunner must not be null");
    this.classifier = Objects.requireNonNull(classifier, "classifier must not be null");
    this.hooks = Objects.requireNonNull(hooks, "hooks must not be null");
    this.toolDispatch = Objects.requireNonNull(toolDispatch, "toolDispatch must not be null");
    this.steeringQueue = Objects.requireNonNull(steeringQueue, "steeringQueue must not be null");
    this.hookContextFactory =
        Objects.requireNonNull(hookContextFactory, "hookContextFactory must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.tokenCounter = Objects.requireNonNull(tokenCounter, "tokenCounter must not be null");
    this.contextCompactor =
        Objects.requireNonNull(contextCompactor, "contextCompactor must not be null");
    this.emitter = new EventEmitter(eventSink, hooks, hookContextFactory, clock);
  }

  /**
   * Run the loop to terminal.
   *
   * @param state per-session mutable state; non-null
   * @param limits per-session limits; non-null
   * @return the terminal {@link ResultMessage}; never null
   * @throws NullPointerException if {@code state} or {@code limits} is null
   */
  public ResultMessage run(SessionState state, SessionLimits limits) {
    Objects.requireNonNull(state, "state must not be null");
    Objects.requireNonNull(limits, "limits must not be null");
    try {
      return runUnsafe(state, limits);
    } catch (Exception e) {
      // Restricted to Exception — Error subtypes (OOM, StackOverflow, LinkageError) leave the JVM
      // in an inconsistent state, so we let them escape and the host process die cleanly.
      return crashTerminate(state, e);
    }
  }

  private static ResultMessage crashTerminate(SessionState state, Exception e) {
    var failure =
        new ResultMessage.ErrorDuringExecution(
            state.sessionId(), SerializedError.of(e), state.usage(), state.cost(), state.elapsed());
    state.setTerminal(failure);
    return failure;
  }

  private ResultMessage runUnsafe(SessionState state, SessionLimits limits) {
    while (true) {
      if (state.isTerminal()) {
        return terminateWithExistingTerminal(state);
      }
      drainAndAppend(state);
      if (state.isTerminal()) {
        return terminateWithExistingTerminal(state);
      }
      if (state.historySnapshot().isEmpty()) {
        return terminate(state, emptyHistoryError(state));
      }
      state.beginTurn();
      checkContextWatermark(state, limits);
      if (state.isTerminal()) {
        return terminateWithExistingTerminal(state);
      }
      var outcome = turnRunner.runTurn(state, limits);
      if (state.isTerminal()) {
        return terminateWithExistingTerminal(state);
      }
      checkContextWatermark(state, limits);
      if (state.isTerminal()) {
        return terminateWithExistingTerminal(state);
      }
      var terminal =
          classifier.classify(
              state,
              limits,
              outcome.finishReason(),
              outcome.assistantContent(),
              outcome.streamError(),
              outcome.streamAttempts(),
              steeringQueue.size() > 0);
      if (terminal.isPresent()) {
        var resolved = handlePreStop(state, terminal.orElseThrow(), outcome);
        if (resolved.isPresent()) {
          return terminate(state, resolved.orElseThrow());
        }
        // Pre-stop hook injected — loop continues
      }
    }
  }

  private void drainAndAppend(SessionState state) {
    var drained = steeringQueue.drain();
    if (drained.isEmpty()) {
      return;
    }
    var accepted = new ArrayList<UserMessage>();
    for (var msg : drained) {
      var ctx = hookContextFactory.apply(state);
      var decision = hooks.fireOnUserMessage(msg, ctx);
      var hookName = decision.firingHookOptional().map(h -> h.name()).orElse(null);
      switch (decision.outcome()) {
        case HookOutcome.Continue ignored -> {
          accepted.add(msg);
          emitter.emit(
              state,
              new QueryEvent.UserMessageReceived(
                  state.sessionId(), state.currentTurnIndex(), clock.instant(), msg));
        }
        case HookOutcome.MutateInput m -> {
          var newText = stringField(m.newInput(), "text");
          var replacement = newText == null ? msg : UserMessage.text(newText);
          accepted.add(replacement);
          emitter.emitHookFired(state, hookName, "OnUserMessageHook", "MutateInput");
          emitter.emit(
              state,
              new QueryEvent.UserMessageReceived(
                  state.sessionId(), state.currentTurnIndex(), clock.instant(), replacement));
        }
        case HookOutcome.Block block -> {
          emitter.emitHookFired(state, hookName, "OnUserMessageHook", "Block");
          var resolvedHookName = hookName == null ? "OnUserMessageHook" : hookName;
          emitter.emit(
              state,
              new QueryEvent.MessageBlocked(
                  state.sessionId(),
                  state.currentTurnIndex(),
                  clock.instant(),
                  msg,
                  resolvedHookName,
                  block.reason()));
        }
        case HookOutcome.Stop s -> {
          emitter.emitHookFired(state, hookName, "OnUserMessageHook", "Stop");
          state.setTerminal(successFor(state, s.result()));
          return;
        }
        case HookOutcome.Inject ignored -> {
          // Inject doesn't have a "this message replaced with another" semantic for
          // OnUserMessage — fall back to treating like Continue.
          accepted.add(msg);
          emitter.emit(
              state,
              new QueryEvent.UserMessageReceived(
                  state.sessionId(), state.currentTurnIndex(), clock.instant(), msg));
        }
      }
    }
    if (accepted.isEmpty()) {
      return;
    }
    var attachments = collectAttachments(accepted);
    if (attachments.isEmpty()) {
      state.appendMessage(Message.user(composeContent(accepted)));
    } else {
      state.appendMessage(Message.user(composeContent(accepted), attachments));
    }
  }

  private static List<InlineFile> collectAttachments(List<UserMessage> messages) {
    var attachments = new ArrayList<InlineFile>();
    for (var m : messages) {
      attachments.addAll(m.attachments());
    }
    return attachments;
  }

  private static String stringField(Map<String, Object> map, String key) {
    var v = map.get(key);
    return v instanceof String s ? s : null;
  }

  private static String composeContent(List<UserMessage> messages) {
    if (messages.size() == 1) {
      return messages.get(0).text();
    }
    var joined = new StringBuilder();
    joined.append("[messages composed: ").append(messages.size()).append("]\n");
    for (var i = 0; i < messages.size(); i++) {
      if (i > 0) {
        joined.append("\n");
      }
      joined.append(messages.get(i).text());
    }
    return joined.toString();
  }

  /**
   * Apply pre-stop hooks to the classifier's terminal verdict. Inject reverses the stop and queues
   * a synthetic message; Stop overrides the result text; Continue/MutateInput/Block confirm the
   * stop unchanged. Returns the resolved terminal, or empty when the hooks declined to stop.
   */
  private Optional<ResultMessage> handlePreStop(
      SessionState state, ResultMessage classifierVerdict, TurnOutcome outcome) {
    if (!(classifierVerdict instanceof ResultMessage.Success)) {
      // Only Success goes through PreStop. Other terminals (cancel, max-turns, errors) bypass.
      return Optional.of(classifierVerdict);
    }
    var response =
        Response.newBuilder()
            .withContent(outcome.assistantContent())
            .withFinishReason(outcome.finishReason())
            .withUsage(outcome.usage())
            .build();
    var ctx = hookContextFactory.apply(state);
    var decision = hooks.firePreStop(response, ctx);
    var hookName = decision.firingHookOptional().map(h -> h.name()).orElse(null);
    return switch (decision.outcome()) {
      case HookOutcome.Continue ignored -> Optional.of(classifierVerdict);
      case HookOutcome.Stop s -> {
        emitter.emitHookFired(state, hookName, "PreStopHook", "Stop");
        yield Optional.of(successFor(state, s.result()));
      }
      case HookOutcome.Inject inj -> {
        emitter.emitHookFired(state, hookName, "PreStopHook", "Inject");
        if (!steeringQueue.offer(UserMessage.text(inj.userMessage()))) {
          LOGGER.log(
              Level.WARNING,
              "PreStopHook ''{0}'' Inject was dropped: steering queue full; session will continue"
                  + " without the injected message",
              hookName);
        }
        yield Optional.empty();
      }
      // MutateInput / Block are not meaningful at PreStop — treat as Continue.
      case HookOutcome.MutateInput m -> Optional.of(classifierVerdict);
      case HookOutcome.Block b -> Optional.of(classifierVerdict);
    };
  }

  private ResultMessage successFor(SessionState state, String text) {
    return new ResultMessage.Success(
        state.sessionId(), text, state.usage(), state.cost(), state.elapsed());
  }

  private ResultMessage terminate(SessionState state, ResultMessage result) {
    state.setTerminal(result);
    emitter.emit(
        state,
        new QueryEvent.LoopEnded(
            state.sessionId(), state.currentTurnIndex(), clock.instant(), result));
    return result;
  }

  private ResultMessage terminateWithExistingTerminal(SessionState state) {
    var existing = state.terminal().orElseThrow();
    emitter.emit(
        state,
        new QueryEvent.LoopEnded(
            state.sessionId(), state.currentTurnIndex(), clock.instant(), existing));
    return existing;
  }

  /**
   * Count tokens in the current history, fire {@link QueryEvent.ContextWarning} on the first
   * crossing of {@link #CONTEXT_WARNING_WATERMARK}, and invoke the {@link ContextCompactor} on
   * crossings of {@link #CONTEXT_COMPACT_WATERMARK}. Successful compaction clears the warning flag
   * so a future re-climb fires the watermark again.
   */
  private void checkContextWatermark(SessionState state, SessionLimits limits) {
    var maxTokens = effectiveMaxContextTokens(limits);
    var tokens = tokenCounter.count(state.historySnapshot());
    var usagePct = (double) tokens / (double) maxTokens;
    if (usagePct >= CONTEXT_WARNING_WATERMARK && state.tryFireContextWarning()) {
      emitter.emit(
          state,
          new QueryEvent.ContextWarning(
              state.sessionId(), state.currentTurnIndex(), clock.instant(), usagePct));
    }
    if (usagePct >= CONTEXT_COMPACT_WATERMARK) {
      runCompactor(state, maxTokens, tokens);
    }
  }

  /**
   * Resolve the effective context-token ceiling for this session by reconciling the user-supplied
   * {@link SessionLimits#maxContextTokens()} cap with the model's documented {@link
   * Model#contextWindow()}. Mirrors Claude Code's {@code wc()} resolver:
   *
   * <ul>
   *   <li>Both known → take the smaller (user cap never permits more than the model actually
   *       supports).
   *   <li>User cap only → honour it as-is. Trusting the deployer is the contract.
   *   <li>Model window only → use {@code window - AUTO_RESERVED_OUTPUT_TOKENS}, leaving headroom
   *       for the next response.
   *   <li>Neither known → fall back to {@link #AUTO_FALLBACK_MAX_CONTEXT_TOKENS}. Last-resort path
   *       for providers that don't yet implement {@code contextWindow()}.
   * </ul>
   *
   * <p>Sentinel: {@code limits.maxContextTokens() == 0} signals "auto, resolve from model". This is
   * the default — library users that want a hard cap call {@link
   * SessionLimits.Builder#withMaxContextTokens(long)}.
   */
  long effectiveMaxContextTokens(SessionLimits limits) {
    var userCap = limits.maxContextTokens();
    var modelWindow = (long) turnRunner.modelContextWindow();
    var modelEffective =
        modelWindow > AUTO_RESERVED_OUTPUT_TOKENS ? modelWindow - AUTO_RESERVED_OUTPUT_TOKENS : 0L;
    if (userCap > 0L && modelEffective > 0L) {
      return Math.min(userCap, modelEffective);
    }
    if (userCap > 0L) {
      return userCap;
    }
    if (modelEffective > 0L) {
      return modelEffective;
    }
    return AUTO_FALLBACK_MAX_CONTEXT_TOKENS;
  }

  /**
   * Invoke the configured {@link ContextCompactor}, swap in the returned history, accumulate any
   * usage reported by the compactor (e.g. summary call spend) — priced against the compactor's own
   * {@link CompactionResult#modelId()} so a cheap summary model isn't billed at the main loop's
   * rate — fire {@link ai.singlr.session.hooks.PreCompactHook} before the compactor runs and {@link
   * ai.singlr.session.hooks.PostCompactHook} after a successful shrink, and emit {@link
   * QueryEvent.ContextEdited} last. A returned identity (same instance) or no-shrink result is
   * treated as a no-op — the compactor opted out for this turn and the warning flag stays set. A
   * throwing compactor is swallowed; the loop continues.
   */
  private void runCompactor(SessionState state, long maxTokens, long tokensBefore) {
    var historyBefore = applyPreCompactHook(state, state.historySnapshot());
    CompactionResult result;
    try {
      result = contextCompactor.compact(historyBefore, state);
    } catch (RuntimeException e) {
      LOGGER.log(Level.WARNING, "context compactor threw; leaving history unchanged this turn", e);
      return;
    }
    if (result == null) {
      return;
    }
    var compactionUsage = result.usage();
    if (compactionUsage.inputTokens() > 0 || compactionUsage.outputTokens() > 0) {
      turnRunner.accumulateUsageAndCost(state, result.modelId(), compactionUsage);
    }
    var historyAfter = result.history();
    if (historyAfter == historyBefore || historyAfter.size() >= historyBefore.size()) {
      return;
    }
    state.replaceHistory(historyAfter);
    var tokensAfter = tokenCounter.count(state.historySnapshot());
    var removedBlocks = historyBefore.size() - historyAfter.size();
    state.resetContextWarningFlag();
    var payload =
        new CompactionPayload(
            historyBefore, historyAfter, tokensBefore, tokensAfter, removedBlocks);
    if (firePostCompactAndCheckStop(state, payload)) {
      return;
    }
    emitter.emit(
        state,
        new QueryEvent.ContextEdited(
            state.sessionId(),
            state.currentTurnIndex(),
            clock.instant(),
            removedBlocks,
            tokensBefore,
            tokensAfter));
  }

  /**
   * Fire {@link ai.singlr.session.hooks.PreCompactHook} and return the history the compactor should
   * operate on. {@link HookOutcome.MutateInput} carrying a {@code "history"} key swaps in the
   * rewritten history; other non-Continue outcomes are logged and treated as Continue.
   */
  private List<Message> applyPreCompactHook(SessionState state, List<Message> historyBefore) {
    var ctx = hookContextFactory.apply(state);
    var decision = hooks.firePreCompact(historyBefore, ctx);
    var hookName = decision.firingHookOptional().map(h -> h.name()).orElse(null);
    return switch (decision.outcome()) {
      case HookOutcome.Continue ignored -> historyBefore;
      case HookOutcome.MutateInput m -> applyPreCompactMutate(state, hookName, m, historyBefore);
      case HookOutcome.Block b -> logUnsupportedPreCompact(hookName, "Block", historyBefore);
      case HookOutcome.Stop s -> logUnsupportedPreCompact(hookName, "Stop", historyBefore);
      case HookOutcome.Inject inj -> logUnsupportedPreCompact(hookName, "Inject", historyBefore);
    };
  }

  private List<Message> applyPreCompactMutate(
      SessionState state,
      String hookName,
      HookOutcome.MutateInput mutate,
      List<Message> historyBefore) {
    var replacement = messageListField(mutate.newInput(), "history");
    if (replacement == null) {
      LOGGER.log(
          Level.WARNING,
          "PreCompactHook ''{0}'' MutateInput missing 'history' key; using original history",
          hookName);
      return historyBefore;
    }
    emitter.emitHookFired(state, hookName, "PreCompactHook", "MutateInput");
    return replacement;
  }

  private static List<Message> logUnsupportedPreCompact(
      String hookName, String outcomeKind, List<Message> fallback) {
    LOGGER.log(
        Level.WARNING,
        "PreCompactHook ''{0}'' {1} is not honored at this phase; using original history",
        new Object[] {hookName, outcomeKind});
    return fallback;
  }

  /**
   * Fire {@link ai.singlr.session.hooks.PostCompactHook}. Returns {@code true} when the hook
   * elected {@link HookOutcome.Stop} so the caller skips the {@link QueryEvent.ContextEdited}
   * emission and lets the loop terminate. Other outcomes (including the unsupported Mutate / Block
   * / Inject) are logged and treated as Continue.
   */
  private boolean firePostCompactAndCheckStop(SessionState state, CompactionPayload payload) {
    var ctx = hookContextFactory.apply(state);
    var decision = hooks.firePostCompact(payload, ctx);
    var hookName = decision.firingHookOptional().map(h -> h.name()).orElse(null);
    return switch (decision.outcome()) {
      case HookOutcome.Continue ignored -> false;
      case HookOutcome.Stop s -> applyPostCompactStop(state, hookName, s);
      case HookOutcome.MutateInput m -> logUnsupportedPostCompact(hookName, "MutateInput");
      case HookOutcome.Block b -> logUnsupportedPostCompact(hookName, "Block");
      case HookOutcome.Inject inj -> logUnsupportedPostCompact(hookName, "Inject");
    };
  }

  private boolean applyPostCompactStop(SessionState state, String hookName, HookOutcome.Stop stop) {
    emitter.emitHookFired(state, hookName, "PostCompactHook", "Stop");
    state.setTerminal(successFor(state, stop.result()));
    return true;
  }

  private static boolean logUnsupportedPostCompact(String hookName, String outcomeKind) {
    LOGGER.log(
        Level.WARNING,
        "PostCompactHook ''{0}'' {1} is not honored at this phase",
        new Object[] {hookName, outcomeKind});
    return false;
  }

  private static List<Message> messageListField(Map<String, Object> map, String key) {
    if (!(map.get(key) instanceof List<?> list)) {
      return null;
    }
    var out = new ArrayList<Message>(list.size());
    for (var element : list) {
      if (!(element instanceof Message m)) {
        return null;
      }
      out.add(m);
    }
    return List.copyOf(out);
  }

  private ResultMessage emptyHistoryError(SessionState state) {
    return new ResultMessage.ErrorDuringExecution(
        state.sessionId(),
        SerializedError.of(
            "EmptyHistory",
            "AgentLoop.run requires at least one user message in the steering queue before "
                + "starting"),
        state.usage(),
        state.cost(),
        state.elapsed());
  }

  /** Internal accessor for tests so they can verify clock injection. */
  Instant nowForTests() {
    return clock.instant();
  }

  /**
   * The bound {@link ToolDispatch}.
   *
   * @return the tool dispatch instance
   */
  public ToolDispatch toolDispatch() {
    return toolDispatch;
  }
}
