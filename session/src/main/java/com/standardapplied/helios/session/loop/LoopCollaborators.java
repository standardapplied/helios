/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.SteeringQueue;
import com.standardapplied.helios.session.hooks.HookContext;
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.permissions.PermissionDecision;
import com.standardapplied.helios.session.tools.ToolBinding;
import java.time.InstantSource;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The per-session collaborators the {@link AgentLoop} and its {@link TurnRunner} share: built once
 * by the session and handed to both, so each constructor names only the collaborators it alone
 * uses.
 *
 * @param hooks priority-sorted hook registry
 * @param toolDispatch dispatcher for the tool calls the model emits
 * @param authorization the mandatory permission check of every call of a registered tool, run on
 *     the call the {@code PreToolUse} hooks leave once canonicalised, with its key; only an {@code
 *     ALLOW} decision runs the call, and anything else, {@code null} or a thrown exception refuses
 *     it
 * @param permission the session's policy; a tool walking below its path withholds every entry a
 *     DENY rule of its own decides, or a DENY or ASK rule of a tool whose content it returns
 * @param steeringQueue per-session inbox the loop drains and into which {@code Inject} outcomes
 *     queue synthetic user messages
 * @param eventSink consumer for every {@link QueryEvent} the loop emits
 * @param hookContextFactory builds the {@link HookContext} of a hook fire or compaction from the
 *     session state
 * @param clock supplies event timestamps
 */
public record LoopCollaborators(
    HookRegistry hooks,
    ToolDispatch toolDispatch,
    Function<ToolBinding.CanonicalCall, PermissionDecision> authorization,
    Permission permission,
    SteeringQueue steeringQueue,
    Consumer<QueryEvent> eventSink,
    Function<SessionState, HookContext> hookContextFactory,
    InstantSource clock) {

  /** The session's secret registry, the one its tool dispatch hands every tool. */
  SecretRegistry secrets() {
    return toolDispatch.sessionContext().secrets();
  }

  HookContext hookContext(SessionState state) {
    return hookContextFactory.apply(state);
  }

  EventEmitter emitter() {
    return new EventEmitter(eventSink, hooks, hookContextFactory, clock, secrets());
  }
}
