/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

/**
 * The hook surface the agent loop fires at every observable lifecycle phase. Hooks are the primary
 * extension point for permission policy, audit, censorship, mid-run mutation, and deterministic
 * test-time injection.
 *
 * <p>{@link com.standardapplied.helios.session.hooks.Hook} is the sealed base of seven
 * phase-specific non-sealed interfaces — {@link
 * com.standardapplied.helios.session.hooks.OnUserMessageHook}, {@link
 * com.standardapplied.helios.session.hooks.PreModelTurnHook}, {@link
 * com.standardapplied.helios.session.hooks.PostModelTurnHook}, {@link
 * com.standardapplied.helios.session.hooks.PreToolUseHook}, {@link
 * com.standardapplied.helios.session.hooks.PostToolUseHook}, {@link
 * com.standardapplied.helios.session.hooks.PreStopHook}, {@link
 * com.standardapplied.helios.session.hooks.OnStreamEventHook} — each describing a place in the loop
 * where the model's intent is decided. Each decisive phase method returns its phase's {@link
 * com.standardapplied.helios.session.hooks.HookOutcome} sub-interface (for example {@code
 * HookOutcome.PreToolUse}), which admits exactly the outcomes that phase honours, so an outcome the
 * phase would ignore does not compile; the phase Javadoc documents what each one does.
 *
 * <p>Hooks are added with {@code SessionOptions.Builder.withHook}; the session runs each phase's
 * hooks by priority, and the first non-{@code Continue} outcome wins, attributed to its hook's name
 * on {@code QueryEvent.HookFired}. {@link com.standardapplied.helios.session.hooks.HookContext}
 * (and {@link com.standardapplied.helios.session.hooks.DefaultHookContext}) carries the per-fire
 * session id / turn / model handle / cancellation token so hooks can take their own actions (e.g.
 * call the model again).
 */
package com.standardapplied.helios.session.hooks;
