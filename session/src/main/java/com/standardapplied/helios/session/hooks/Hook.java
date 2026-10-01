/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.hooks;

/**
 * First-class extension point in the agent loop. Hooks observe and optionally steer specific
 * lifecycle phases — before/after a tool call, before/after a model turn, before stop, on each user
 * message, on each stream event.
 *
 * <p>The sealed permits list names the seven phase-specific subtypes. Each is a functional
 * interface with a single phase-shaped method, and each subtype defines its own semantics for the
 * {@link HookOutcome} values it returns (see the spec §9.3 table). The {@link #name()} is used in
 * audit events and error messages; the {@link #priority()} drives the firing order (low → high),
 * ties broken by registration order.
 *
 * <p>Hook misbehavior cannot abort the session. If a hook throws, the orchestrator (see {@link
 * HookRegistry}) treats the outcome as {@link HookOutcome.Continue} and logs/audits the failure.
 * This is by design — see spec §9.4.
 */
public sealed interface Hook
    permits PreToolUseHook,
        PostToolUseHook,
        PreModelTurnHook,
        PostModelTurnHook,
        PreStopHook,
        PreCompactHook,
        PostCompactHook,
        OnUserMessageHook,
        OnStreamEventHook {

  /**
   * The hook's stable name. Surfaced in audit events, error messages, and the {@link
   * ai.singlr.session.QueryEvent.HookFired HookFired} event once that subtype lands. Must be
   * non-blank and stable across the hook's lifetime.
   *
   * @return non-blank, non-null identifier
   */
  String name();

  /**
   * Firing priority. Lower values fire earlier. The default is {@code 100}; permission hooks
   * conventionally use {@code 50} so they evaluate before user-supplied hooks.
   *
   * @return non-negative priority
   */
  default int priority() {
    return 100;
  }
}
