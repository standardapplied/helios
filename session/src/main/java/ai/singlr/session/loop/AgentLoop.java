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
   */
  static final double CONTEXT_WARNING_WATERMARK = 0.85;

  /**
   * Watermark fraction of {@link SessionLimits#maxContextTokens()} at which the loop invokes the
   * configured {@link ContextCompactor}. 0.95 keeps a 5% safety margin against the underlying
   * context window; below the warning watermark we'd compact too eagerly.
   */
  static final double CONTEXT_COMPACT_WATERMARK = 0.95;

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
    var maxTokens = limits.maxContextTokens();
    if (maxTokens <= 0) {
      return;
    }
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
   * Invoke the configured {@link ContextCompactor}, swap in the returned history, accumulate any
   * usage reported by the compactor (e.g. summary call spend) through the {@link
   * TurnRunner#accumulateUsageAndCost} helper, and emit {@link QueryEvent.ContextEdited} when the
   * compactor actually shrank the history. A returned identity (same instance) or no-shrink result
   * is treated as a no-op — the compactor opted out for this turn and the warning flag stays set. A
   * throwing compactor is swallowed; the loop continues.
   */
  private void runCompactor(SessionState state, long maxTokens, long tokensBefore) {
    var historyBefore = state.historySnapshot();
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
      turnRunner.accumulateUsageAndCost(state, compactionUsage);
    }
    var historyAfter = result.history();
    if (historyAfter == historyBefore || historyAfter.size() >= historyBefore.size()) {
      return;
    }
    state.replaceHistory(historyAfter);
    var tokensAfter = tokenCounter.count(state.historySnapshot());
    var removedBlocks = historyBefore.size() - historyAfter.size();
    state.resetContextWarningFlag();
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
