/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.hooks;

import ai.singlr.core.model.Message;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.session.QueryEvent;
import ai.singlr.session.UserMessage;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Priority-sorted hook dispatcher. One instance per session, built from the hook list supplied via
 * {@link ai.singlr.session.SessionOptions.Builder#withHook(Hook)}.
 *
 * <p>Hooks of each phase are sorted by {@link Hook#priority()} (low → high), then by registration
 * order for ties. For non-observe phases, firing returns the first non-{@link HookOutcome.Continue}
 * outcome it sees — see spec §9.4. Stream-event hooks all fire (observe-only).
 *
 * <p>The decisive {@code fire*} methods return a {@link HookDecision} pairing the outcome with the
 * firing {@link Hook}, so the orchestrator can attribute {@link QueryEvent.HookFired} events to the
 * actual hook (not the phase name).
 *
 * <p>If a hook throws, the orchestrator treats the outcome as {@link HookOutcome.Continue} and logs
 * at {@code WARNING} — see spec §9.4. Hook misbehavior cannot abort the session.
 *
 * <h2>Thread-safety</h2>
 *
 * Immutable after construction. Safe to share across threads.
 */
public final class HookRegistry {

  private static final Logger LOGGER = Logger.getLogger(HookRegistry.class.getName());

  private final List<PreToolUseHook> preToolUse;
  private final List<PostToolUseHook> postToolUse;
  private final List<PreModelTurnHook> preModelTurn;
  private final List<PostModelTurnHook> postModelTurn;
  private final List<PreStopHook> preStop;
  private final List<PreCompactHook> preCompact;
  private final List<PostCompactHook> postCompact;
  private final List<OnUserMessageHook> onUserMessage;
  private final List<OnStreamEventHook> onStreamEvent;
  private final Map<Class<? extends Hook>, List<? extends Hook>> hooksByPhase;

  /**
   * Build a registry from the given hooks. Hooks are partitioned by phase via their sealed permits
   * and sorted by priority.
   *
   * @param hooks the hooks; non-null but may be empty
   * @throws NullPointerException if {@code hooks} is null or contains null elements
   */
  public HookRegistry(List<? extends Hook> hooks) {
    Objects.requireNonNull(hooks, "hooks must not be null");
    for (var h : hooks) {
      Objects.requireNonNull(h, "hooks must not contain null");
    }
    this.preToolUse = sortByPriority(hooks, PreToolUseHook.class);
    this.postToolUse = sortByPriority(hooks, PostToolUseHook.class);
    this.preModelTurn = sortByPriority(hooks, PreModelTurnHook.class);
    this.postModelTurn = sortByPriority(hooks, PostModelTurnHook.class);
    this.preStop = sortByPriority(hooks, PreStopHook.class);
    this.preCompact = sortByPriority(hooks, PreCompactHook.class);
    this.postCompact = sortByPriority(hooks, PostCompactHook.class);
    this.onUserMessage = sortByPriority(hooks, OnUserMessageHook.class);
    this.onStreamEvent = sortByPriority(hooks, OnStreamEventHook.class);
    var byPhase = new HashMap<Class<? extends Hook>, List<? extends Hook>>();
    byPhase.put(PreToolUseHook.class, preToolUse);
    byPhase.put(PostToolUseHook.class, postToolUse);
    byPhase.put(PreModelTurnHook.class, preModelTurn);
    byPhase.put(PostModelTurnHook.class, postModelTurn);
    byPhase.put(PreStopHook.class, preStop);
    byPhase.put(PreCompactHook.class, preCompact);
    byPhase.put(PostCompactHook.class, postCompact);
    byPhase.put(OnUserMessageHook.class, onUserMessage);
    byPhase.put(OnStreamEventHook.class, onStreamEvent);
    this.hooksByPhase = Map.copyOf(byPhase);
  }

  /**
   * Empty registry — no hooks of any phase.
   *
   * @return a fresh empty registry
   */
  public static HookRegistry empty() {
    return new HookRegistry(List.of());
  }

  private static <H extends Hook> List<H> sortByPriority(
      List<? extends Hook> hooks, Class<H> phase) {
    return hooks.stream()
        .filter(phase::isInstance)
        .map(phase::cast)
        .sorted(Comparator.comparingInt(Hook::priority))
        .toList();
  }

  /**
   * Number of registered hooks of the given phase. Useful for tests and observability.
   *
   * @param phase the phase class
   * @return non-negative count
   */
  public int countOf(Class<? extends Hook> phase) {
    var phaseHooks = hooksByPhase.get(phase);
    return phaseHooks == null ? 0 : phaseHooks.size();
  }

  /**
   * Fire every registered {@link PreToolUseHook} until one returns a non-{@link
   * HookOutcome.Continue} outcome. Returns {@link HookDecision#proceed()} if no hook decides
   * anything.
   *
   * @param call the impending tool call; non-null
   * @param ctx the per-invocation context; non-null
   * @return the first decisive decision, or {@link HookDecision#proceed()}
   */
  public HookDecision firePreToolUse(ToolCall call, HookContext ctx) {
    Objects.requireNonNull(call, "call must not be null");
    Objects.requireNonNull(ctx, "ctx must not be null");
    return fireDeciding(preToolUse, hook -> hook.beforeTool(call, ctx));
  }

  /**
   * Fire every registered {@link PostToolUseHook}.
   *
   * @param call the call that ran; non-null
   * @param result the tool result; non-null
   * @param ctx the per-invocation context; non-null
   * @return the first decisive decision, or {@link HookDecision#proceed()}
   */
  public HookDecision firePostToolUse(ToolCall call, ToolResult result, HookContext ctx) {
    Objects.requireNonNull(call, "call must not be null");
    Objects.requireNonNull(result, "result must not be null");
    Objects.requireNonNull(ctx, "ctx must not be null");
    return fireDeciding(postToolUse, hook -> hook.afterTool(call, result, ctx));
  }

  /**
   * Fire every registered {@link PreModelTurnHook}.
   *
   * @param history the history about to be sent; non-null
   * @param ctx the per-invocation context; non-null
   * @return the first decisive decision, or {@link HookDecision#proceed()}
   */
  public HookDecision firePreModelTurn(List<Message> history, HookContext ctx) {
    Objects.requireNonNull(history, "history must not be null");
    Objects.requireNonNull(ctx, "ctx must not be null");
    return fireDeciding(preModelTurn, hook -> hook.beforeModelTurn(history, ctx));
  }

  /**
   * Fire every registered {@link PostModelTurnHook}.
   *
   * @param response the model response; non-null
   * @param ctx the per-invocation context; non-null
   * @return the first decisive decision, or {@link HookDecision#proceed()}
   */
  public HookDecision firePostModelTurn(Response<?> response, HookContext ctx) {
    Objects.requireNonNull(response, "response must not be null");
    Objects.requireNonNull(ctx, "ctx must not be null");
    return fireDeciding(postModelTurn, hook -> hook.afterModelTurn(response, ctx));
  }

  /**
   * Fire every registered {@link PreStopHook}.
   *
   * @param stopResponse the response the loop is about to declare terminal; non-null
   * @param ctx the per-invocation context; non-null
   * @return the first decisive decision, or {@link HookDecision#proceed()}
   */
  public HookDecision firePreStop(Response<?> stopResponse, HookContext ctx) {
    Objects.requireNonNull(stopResponse, "stopResponse must not be null");
    Objects.requireNonNull(ctx, "ctx must not be null");
    return fireDeciding(preStop, hook -> hook.beforeStop(stopResponse, ctx));
  }

  /**
   * Fire every registered {@link PreCompactHook}.
   *
   * @param history the history about to be passed to the configured {@code ContextCompactor};
   *     non-null
   * @param ctx the per-invocation context; non-null
   * @return the first decisive decision, or {@link HookDecision#proceed()}
   */
  public HookDecision firePreCompact(List<Message> history, HookContext ctx) {
    Objects.requireNonNull(history, "history must not be null");
    Objects.requireNonNull(ctx, "ctx must not be null");
    return fireDeciding(preCompact, hook -> hook.beforeCompact(history, ctx));
  }

  /**
   * Fire every registered {@link PostCompactHook}.
   *
   * @param payload the before/after compaction snapshot; non-null
   * @param ctx the per-invocation context; non-null
   * @return the first decisive decision, or {@link HookDecision#proceed()}
   */
  public HookDecision firePostCompact(CompactionPayload payload, HookContext ctx) {
    Objects.requireNonNull(payload, "payload must not be null");
    Objects.requireNonNull(ctx, "ctx must not be null");
    return fireDeciding(postCompact, hook -> hook.afterCompact(payload, ctx));
  }

  /**
   * Fire every registered {@link OnUserMessageHook}.
   *
   * @param msg the user message; non-null
   * @param ctx the per-invocation context; non-null
   * @return the first decisive decision, or {@link HookDecision#proceed()}
   */
  public HookDecision fireOnUserMessage(UserMessage msg, HookContext ctx) {
    Objects.requireNonNull(msg, "msg must not be null");
    Objects.requireNonNull(ctx, "ctx must not be null");
    return fireDeciding(onUserMessage, hook -> hook.onUserMessage(msg, ctx));
  }

  /**
   * Fire every registered {@link OnStreamEventHook}. Observe-only: any hook misbehavior is logged
   * but does not propagate.
   *
   * @param event the event just emitted; non-null
   * @param ctx the per-invocation context; non-null
   */
  public void fireOnStreamEvent(QueryEvent event, HookContext ctx) {
    Objects.requireNonNull(event, "event must not be null");
    Objects.requireNonNull(ctx, "ctx must not be null");
    for (var hook : onStreamEvent) {
      try {
        hook.onEvent(event, ctx);
      } catch (RuntimeException ex) {
        LOGGER.log(Level.WARNING, "hook " + hook.name() + " threw on stream event", ex);
      }
    }
  }

  /**
   * Walk the phase's hooks in priority order, return the first non-Continue {@link HookDecision},
   * or {@link HookDecision#proceed()} when every hook continued. Hook exceptions are swallowed
   * (logged) and treated as Continue — see spec §9.4.
   */
  private <H extends Hook> HookDecision fireDeciding(
      List<H> phaseHooks, Function<H, HookOutcome> body) {
    for (var hook : phaseHooks) {
      var outcome = safeFire(hook, body);
      if (!(outcome instanceof HookOutcome.Continue)) {
        return HookDecision.of(hook, outcome);
      }
    }
    return HookDecision.proceed();
  }

  private static <H extends Hook> HookOutcome safeFire(H hook, Function<H, HookOutcome> body) {
    try {
      var outcome = body.apply(hook);
      if (outcome == null) {
        LOGGER.warning("hook " + hook.name() + " returned null outcome; treating as Continue");
        return HookOutcome.cont();
      }
      return outcome;
    } catch (RuntimeException ex) {
      LOGGER.log(Level.WARNING, "hook " + hook.name() + " threw; treating as Continue", ex);
      return HookOutcome.cont();
    }
  }
}
