/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.hooks;

/**
 * First-class extension point in the agent loop. Hooks observe and optionally steer specific
 * lifecycle phases — before/after a tool call, before/after a model turn, before stop, on each user
 * message, on each stream event.
 *
 * <p>The sealed permits list names the seven phase-specific subtypes. Each is a functional
 * interface with a single phase-shaped method returning its phase's {@link HookOutcome} type, which
 * admits only the outcomes that phase honours. The {@link #name()} is used in audit events and
 * error messages; the {@link #priority()} drives the firing order (low → high), ties broken by
 * registration order.
 *
 * <p>A hook that throws a {@link RuntimeException} or returns {@code null} cannot abort the
 * session: a {@link PreToolUseHook} fails closed as a {@link HookOutcome.Block}, any other hook is
 * treated as {@link HookOutcome.Continue}, and {@link HookRegistry} logs the failure. An {@link
 * Error} escapes.
 */
public sealed interface Hook
    permits PreToolUseHook,
        PostToolUseHook,
        PreModelTurnHook,
        PostModelTurnHook,
        PreStopHook,
        OnUserMessageHook,
        OnStreamEventHook {

  /**
   * The hook's name, surfaced in audit events, log lines and the {@link
   * com.standardapplied.helios.session.QueryEvent.HookFired HookFired}, {@link
   * com.standardapplied.helios.session.QueryEvent.ToolBlocked ToolBlocked} and {@link
   * com.standardapplied.helios.session.QueryEvent.ToolMutated ToolMutated} events. {@link
   * HookRegistry} reads it once, when it is built, and uses that name for the hook's lifetime; it
   * rejects a null or blank name and the reserved {@link
   * com.standardapplied.helios.session.permissions.Permission#NAME Permission}, so a session with
   * such a hook fails to be created. Each phase interface defaults it to the implementing class's
   * simple name, or to its binary name (e.g. {@code com.acme.Policies$1}) for an anonymous class.
   *
   * @return non-blank, non-null identifier
   */
  String name();

  /**
   * Firing priority. Lower values fire earlier. The default is {@code 100}. The session's
   * permission check is not a hook: it runs after every {@code PreToolUse} hook, whatever its
   * priority.
   *
   * @return non-negative priority
   */
  default int priority() {
    return 100;
  }
}
