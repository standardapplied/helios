/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.SerializedError;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.hooks.Hook;
import com.standardapplied.helios.session.hooks.HookContext;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.OnStreamEventHook;
import com.standardapplied.helios.session.hooks.OnUserMessageHook;
import com.standardapplied.helios.session.hooks.PostModelTurnHook;
import com.standardapplied.helios.session.hooks.PostToolUseHook;
import com.standardapplied.helios.session.hooks.PreModelTurnHook;
import com.standardapplied.helios.session.hooks.PreStopHook;
import com.standardapplied.helios.session.hooks.PreToolUseHook;
import com.standardapplied.helios.session.permissions.Permission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
 * <p><b>{@code PreToolUse} hooks are guards and chain</b>, as {@link PreToolUseHook} describes;
 * {@link #firePreToolUse} returns the chain's {@link PreToolUseResult}. A guard that fails ends the
 * chain as a {@code Block} with reason {@code hook threw <exception class>: <message>} or {@code
 * hook returned null}, logged at {@code WARNING}.
 *
 * <p>In every other phase a hook that throws a {@link RuntimeException} or returns {@code null} is
 * treated as {@link HookOutcome.Continue} and logged at {@code WARNING}, so the next hook of the
 * phase runs — see spec §9.4. An {@link Error} escapes from every phase.
 *
 * <p>A log record of a hook that threw carries the exception's {@linkplain
 * SerializedError#redactedForLog redacted copy}, never the exception itself, so no secret of the
 * session's registry reaches a log handler.
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
  private final List<Registered<OnUserMessageHook>> onUserMessage;
  private final List<Registered<OnStreamEventHook>> onStreamEvent;
  private final SecretRegistry secrets;

  /** A hook with the name the registry read from it when it was built. */
  private record Registered<H extends Hook>(H hook, String name) {}

  /**
   * Build a registry from the given hooks. Hooks are partitioned by phase via their sealed permits
   * and sorted by priority. Each hook's {@link Hook#name()} is called exactly once, here; an
   * exception it throws propagates unchanged.
   *
   * @param hooks the hooks; non-null but may be empty
   * @param secrets the session's secret registry, which a logged hook failure is redacted against;
   *     non-null
   * @throws NullPointerException if {@code hooks} or {@code secrets} is null, {@code hooks}
   *     contains null elements, or holds a hook whose name is null
   * @throws IllegalArgumentException if a hook's name is blank or {@link Permission#NAME}
   */
  public HookRegistry(List<? extends Hook> hooks, SecretRegistry secrets) {
    Objects.requireNonNull(hooks, "hooks must not be null");
    this.secrets = Objects.requireNonNull(secrets, "secrets must not be null");
    var registered = hooks.stream().map(HookRegistry::register).toList();
    this.preToolUse = sortByPriority(registered, PreToolUseHook.class);
    this.postToolUse = sortByPriority(registered, PostToolUseHook.class);
    this.preModelTurn = sortByPriority(registered, PreModelTurnHook.class);
    this.postModelTurn = sortByPriority(registered, PostModelTurnHook.class);
    this.preStop = sortByPriority(registered, PreStopHook.class);
    this.onUserMessage = sortByPriority(registered, OnUserMessageHook.class);
    this.onStreamEvent = sortByPriority(registered, OnStreamEventHook.class);
  }

  /**
   * Empty registry — no hooks of any phase, and an empty secret registry.
   *
   * @return a fresh empty registry
   */
  public static HookRegistry empty() {
    return new HookRegistry(List.of(), new SecretRegistry());
  }

  private static Registered<Hook> register(Hook hook) {
    Objects.requireNonNull(hook, "hooks must not contain null");
    var type = hook.getClass().getName();
    var name = Objects.requireNonNull(hook.name(), () -> "hook " + type + " returned a null name");
    if (Strings.isBlank(name)) {
      throw new IllegalArgumentException("hook " + type + " returned a blank name");
    }
    if (name.equals(Permission.NAME)) {
      throw new IllegalArgumentException("hook " + type + " uses the reserved name " + name);
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
    var mutations = new ArrayList<HookDecision<HookOutcome.MutateArgs>>();
    for (var registered : preToolUse) {
      var outcome = guard(registered, current, ctx);
      if (outcome instanceof HookOutcome.MutateArgs mutate) {
        mutations.add(HookDecision.of(registered.name(), mutate));
        current = new ToolCall(current.id(), current.name(), mutate.args());
      } else if (!(outcome instanceof HookOutcome.Continue)) {
        return new PreToolUseResult(
            current, mutations, HookDecision.of(registered.name(), outcome));
      }
    }
    return new PreToolUseResult(current, mutations, new HookDecision<>(null, HookOutcome.cont()));
  }

  private HookOutcome.PreToolUse guard(
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
          Level.WARNING,
          "PreToolUse hook "
              + registered.name()
              + " threw "
              + ex.getClass().getName()
              + "; refusing the call",
          redacted(ex));
      return HookOutcome.block("hook threw " + ex.getClass().getName() + ": " + ex.getMessage());
    }
  }

  /**
   * Fire every registered {@link PostToolUseHook}.
   *
   * @param call the call that ran; non-null
   * @param result the tool result; non-null
   * @param ctx the per-invocation context; non-null
   * @return the first decisive decision, or a Continue decision
   */
  public HookDecision<HookOutcome.PostToolUse> firePostToolUse(
      ToolCall call, ToolResult result, HookContext ctx) {
    Objects.requireNonNull(call, "call must not be null");
    Objects.requireNonNull(result, "result must not be null");
    Objects.requireNonNull(ctx, "ctx must not be null");
    return fireDeciding(
        postToolUse,
        hook -> hook.afterTool(call, result, ctx),
        new HookDecision<>(null, HookOutcome.cont()));
  }

  /**
   * Fire every registered {@link PreModelTurnHook}.
   *
   * @param history the history about to be sent; non-null
   * @param ctx the per-invocation context; non-null
   * @return the first decisive decision, or a Continue decision
   */
  public HookDecision<HookOutcome.PreModelTurn> firePreModelTurn(
      List<Message> history, HookContext ctx) {
    Objects.requireNonNull(history, "history must not be null");
    Objects.requireNonNull(ctx, "ctx must not be null");
    return fireDeciding(
        preModelTurn,
        hook -> hook.beforeModelTurn(history, ctx),
        new HookDecision<>(null, HookOutcome.cont()));
  }

  /**
   * Fire every registered {@link PostModelTurnHook}.
   *
   * @param response the model response; non-null
   * @param ctx the per-invocation context; non-null
   * @return the first decisive decision, or a Continue decision
   */
  public HookDecision<HookOutcome.PostModelTurn> firePostModelTurn(
      Response<?> response, HookContext ctx) {
    Objects.requireNonNull(response, "response must not be null");
    Objects.requireNonNull(ctx, "ctx must not be null");
    return fireDeciding(
        postModelTurn,
        hook -> hook.afterModelTurn(response, ctx),
        new HookDecision<>(null, HookOutcome.cont()));
  }

  /**
   * Fire every registered {@link PreStopHook}.
   *
   * @param stopResponse the response the loop is about to declare terminal; non-null
   * @param ctx the per-invocation context; non-null
   * @return the first decisive decision, or a Continue decision
   */
  public HookDecision<HookOutcome.PreStop> firePreStop(Response<?> stopResponse, HookContext ctx) {
    Objects.requireNonNull(stopResponse, "stopResponse must not be null");
    Objects.requireNonNull(ctx, "ctx must not be null");
    return fireDeciding(
        preStop,
        hook -> hook.beforeStop(stopResponse, ctx),
        new HookDecision<>(null, HookOutcome.cont()));
  }

  /**
   * Fire every registered {@link OnUserMessageHook}.
   *
   * @param msg the user message; non-null
   * @param ctx the per-invocation context; non-null
   * @return the first decisive decision, or a Continue decision
   */
  public HookDecision<HookOutcome.OnUserMessage> fireOnUserMessage(
      UserMessage msg, HookContext ctx) {
    Objects.requireNonNull(msg, "msg must not be null");
    Objects.requireNonNull(ctx, "ctx must not be null");
    return fireDeciding(
        onUserMessage,
        hook -> hook.onUserMessage(msg, ctx),
        new HookDecision<>(null, HookOutcome.cont()));
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
        LOGGER.log(
            Level.WARNING, "hook " + registered.name() + " threw on stream event", redacted(ex));
      }
    }
  }

  /**
   * Walk the phase's hooks in priority order, return the first non-Continue {@link HookDecision},
   * or {@code proceed} when every hook continued. Hook exceptions are swallowed (logged) and
   * treated as Continue — see spec §9.4. The caller builds {@code proceed} because only the phase
   * type knows that it admits {@link HookOutcome.Continue}.
   */
  private <H extends Hook, O extends HookOutcome> HookDecision<O> fireDeciding(
      List<Registered<H>> phaseHooks, Function<H, O> body, HookDecision<O> proceed) {
    for (var registered : phaseHooks) {
      var outcome = safeFire(registered, body);
      if (outcome.isPresent() && !(outcome.get() instanceof HookOutcome.Continue)) {
        return HookDecision.of(registered.name(), outcome.get());
      }
    }
    return proceed;
  }

  /** The hook's outcome, or empty when it threw or returned {@code null} (both logged). */
  private <H extends Hook, O extends HookOutcome> Optional<O> safeFire(
      Registered<H> registered, Function<H, O> body) {
    try {
      var outcome = body.apply(registered.hook());
      if (outcome == null) {
        LOGGER.warning(
            "hook " + registered.name() + " returned null outcome; treating as Continue");
      }
      return Optional.ofNullable(outcome);
    } catch (RuntimeException ex) {
      LOGGER.log(
          Level.WARNING,
          "hook " + registered.name() + " threw; treating as Continue",
          redacted(ex));
      return Optional.empty();
    }
  }

  private Throwable redacted(RuntimeException ex) {
    return SerializedError.redactedForLog(ex, secrets.redactor());
  }
}
