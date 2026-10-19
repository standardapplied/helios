/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.hooks;

import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.UserMessage;
import java.util.ArrayList;
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
 * {@link com.standardapplied.helios.session.SessionOptions.Builder#withHook(Hook)}.
 *
 * <p>Hooks of each phase are sorted by {@link Hook#priority()} (low → high), then by registration
 * order for ties. For decisive phases other than {@code PreToolUse}, firing returns the first
 * non-{@link HookOutcome.Continue} outcome it sees — see spec §9.4. Stream-event hooks all fire
 * (observe-only).
 *
 * <p>The decisive {@code fire*} methods return a {@link HookDecision} pairing the outcome with the
 * name the registry read from the firing hook when it was built, so the orchestrator can attribute
 * {@link QueryEvent.HookFired} events to the actual hook (not the phase name).
 *
 * <p><b>{@code PreToolUse} hooks are guards and chain.</b> Each hook receives the call as the hooks
 * before it left it: {@link HookOutcome.MutateArgs} passes the same call with the new arguments to
 * the next hook, and {@link HookOutcome.Block}, {@link HookOutcome.Inject} and {@link
 * HookOutcome.Stop} end the chain; any other outcome passes the call unchanged. A hook that throws
 * a {@link RuntimeException} or returns {@code null} fails closed: it ends the chain as a {@code
 * Block} with reason {@code hook threw <exception class>: <message>} or {@code hook returned null},
 * logged at {@code WARNING}. {@link #firePreToolUse} returns the result as a {@link
 * PreToolUseResult}. The session's permission check is not a hook: it runs after the chain, on the
 * final call.
 *
 * <p>In every other phase a hook that throws a {@link RuntimeException} or returns {@code null} is
 * treated as {@link HookOutcome.Continue} and logged at {@code WARNING}, so the next hook of the
 * phase runs — see spec §9.4. An {@link Error} escapes from every phase.
 *
 * <h2>Thread-safety</h2>
 *
 * Immutable after construction. Safe to share across threads.
 */
public final class HookRegistry {

  private static final Logger LOGGER = Logger.getLogger(HookRegistry.class.getName());

  private final List<Registered<PreToolUseHook>> preToolUse;
  private final List<Registered<PostToolUseHook>> postToolUse;
  private final List<Registered<PreModelTurnHook>> preModelTurn;
  private final List<Registered<PostModelTurnHook>> postModelTurn;
  private final List<Registered<PreStopHook>> preStop;
  private final List<Registered<PreCompactHook>> preCompact;
  private final List<Registered<PostCompactHook>> postCompact;
  private final List<Registered<OnUserMessageHook>> onUserMessage;
  private final List<Registered<OnStreamEventHook>> onStreamEvent;
  private final Map<Class<? extends Hook>, List<?>> hooksByPhase;

  /** A hook with the name the registry read from it when it was built. */
  private record Registered<H extends Hook>(H hook, String name) {}

  /**
   * Build a registry from the given hooks. Hooks are partitioned by phase via their sealed permits
   * and sorted by priority. Each hook's {@link Hook#name()} is called exactly once, here; an
   * exception it throws propagates unchanged.
   *
   * @param hooks the hooks; non-null but may be empty
   * @throws NullPointerException if {@code hooks} is null, contains null elements, or holds a hook
   *     whose name is null
   * @throws IllegalArgumentException if a hook's name is blank
   */
  public HookRegistry(List<? extends Hook> hooks) {
    Objects.requireNonNull(hooks, "hooks must not be null");
    var registered = hooks.stream().map(HookRegistry::register).toList();
    this.preToolUse = sortByPriority(registered, PreToolUseHook.class);
    this.postToolUse = sortByPriority(registered, PostToolUseHook.class);
    this.preModelTurn = sortByPriority(registered, PreModelTurnHook.class);
    this.postModelTurn = sortByPriority(registered, PostModelTurnHook.class);
    this.preStop = sortByPriority(registered, PreStopHook.class);
    this.preCompact = sortByPriority(registered, PreCompactHook.class);
    this.postCompact = sortByPriority(registered, PostCompactHook.class);
    this.onUserMessage = sortByPriority(registered, OnUserMessageHook.class);
    this.onStreamEvent = sortByPriority(registered, OnStreamEventHook.class);
    var byPhase = new HashMap<Class<? extends Hook>, List<?>>();
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

  private static Registered<Hook> register(Hook hook) {
    Objects.requireNonNull(hook, "hooks must not contain null");
    var type = hook.getClass().getName();
    var name = Objects.requireNonNull(hook.name(), () -> "hook " + type + " returned a null name");
    if (Strings.isBlank(name)) {
      throw new IllegalArgumentException("hook " + type + " returned a blank name");
    }
    return new Registered<>(hook, name);
  }

  private static <H extends Hook> List<Registered<H>> sortByPriority(
      List<Registered<Hook>> hooks, Class<H> phase) {
    return hooks.stream()
        .filter(registered -> phase.isInstance(registered.hook()))
        .map(registered -> new Registered<>(phase.cast(registered.hook()), registered.name()))
        .sorted(Comparator.comparingInt(registered -> registered.hook().priority()))
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
   * Run the {@link PreToolUseHook} chain on {@code call}: each hook sees the call as the hooks
   * before it left it, until one blocks, injects or stops, or a hook fails. See the class javadoc.
   *
   * @param call the call the model emitted; non-null
   * @param ctx the per-invocation context; non-null
   * @return the final call, its argument mutations and the decision that ended the chain
   */
  public PreToolUseResult firePreToolUse(ToolCall call, HookContext ctx) {
    Objects.requireNonNull(call, "call must not be null");
    Objects.requireNonNull(ctx, "ctx must not be null");
    var current = call;
    var mutations = new ArrayList<HookDecision>();
    for (var registered : preToolUse) {
      var outcome = guard(registered, current, ctx);
      if (outcome instanceof HookOutcome.MutateArgs mutate) {
        mutations.add(HookDecision.of(registered.name(), mutate));
        current = new ToolCall(current.id(), current.name(), mutate.args());
      } else if (outcome instanceof HookOutcome.Block
          || outcome instanceof HookOutcome.Inject
          || outcome instanceof HookOutcome.Stop) {
        return new PreToolUseResult(
            current, mutations, HookDecision.of(registered.name(), outcome));
      }
    }
    return new PreToolUseResult(current, mutations, HookDecision.proceed());
  }

  private static HookOutcome guard(
      Registered<PreToolUseHook> registered, ToolCall call, HookContext ctx) {
    try {
      var outcome = registered.hook().beforeTool(call, ctx);
      if (outcome == null) {
        LOGGER.warning(
            "PreToolUse hook " + registered.name() + " returned null; refusing the call");
        return HookOutcome.block("hook returned null");
      }
      return outcome;
    } catch (RuntimeException ex) {
      LOGGER.log(
          Level.WARNING, "PreToolUse hook " + registered.name() + " threw; refusing the call", ex);
      return HookOutcome.block("hook threw " + ex.getClass().getName() + ": " + ex.getMessage());
    }
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
    for (var registered : onStreamEvent) {
      try {
        registered.hook().onEvent(event, ctx);
      } catch (RuntimeException ex) {
        LOGGER.log(Level.WARNING, "hook " + registered.name() + " threw on stream event", ex);
      }
    }
  }

  /**
   * Walk the phase's hooks in priority order, return the first non-Continue {@link HookDecision},
   * or {@link HookDecision#proceed()} when every hook continued. Hook exceptions are swallowed
   * (logged) and treated as Continue — see spec §9.4.
   */
  private <H extends Hook> HookDecision fireDeciding(
      List<Registered<H>> phaseHooks, Function<H, HookOutcome> body) {
    for (var registered : phaseHooks) {
      var outcome = safeFire(registered, body);
      if (!(outcome instanceof HookOutcome.Continue)) {
        return HookDecision.of(registered.name(), outcome);
      }
    }
    return HookDecision.proceed();
  }

  private static <H extends Hook> HookOutcome safeFire(
      Registered<H> registered, Function<H, HookOutcome> body) {
    try {
      var outcome = body.apply(registered.hook());
      if (outcome == null) {
        LOGGER.warning(
            "hook " + registered.name() + " returned null outcome; treating as Continue");
        return HookOutcome.cont();
      }
      return outcome;
    } catch (RuntimeException ex) {
      LOGGER.log(Level.WARNING, "hook " + registered.name() + " threw; treating as Continue", ex);
      return HookOutcome.cont();
    }
  }
}
