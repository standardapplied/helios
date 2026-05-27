/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

/**
 * The hook surface the agent loop fires at every observable lifecycle phase. Hooks are the primary
 * extension point for permission policy, audit, censorship, mid-run mutation, and deterministic
 * test-time injection.
 *
 * <p>{@link ai.singlr.session.hooks.Hook} is the sealed base of seven phase-specific non-sealed
 * interfaces — {@link ai.singlr.session.hooks.OnUserMessageHook}, {@link
 * ai.singlr.session.hooks.PreModelTurnHook}, {@link ai.singlr.session.hooks.PostModelTurnHook},
 * {@link ai.singlr.session.hooks.PreToolUseHook}, {@link ai.singlr.session.hooks.PostToolUseHook},
 * {@link ai.singlr.session.hooks.PreStopHook}, {@link ai.singlr.session.hooks.OnStreamEventHook} —
 * each describing a place in the loop where the model's intent is decided. Hooks return a {@link
 * ai.singlr.session.hooks.HookOutcome} (sealed {@code Continue} / {@code MutateArgs} / {@code
 * MutateHistory} / {@code MutateText} / {@code MutateResult} / {@code Block} / {@code Inject} /
 * {@code Stop}); not every outcome is meaningful at every phase, and the phase Javadoc documents
 * what each one does.
 *
 * <p>{@link ai.singlr.session.hooks.HookRegistry} is the priority-sorted, type-fanned dispatcher
 * the loop calls. {@link ai.singlr.session.hooks.HookDecision} pairs the firing hook (for
 * observability — surfaced on {@code QueryEvent.HookFired}) with the outcome. {@link
 * ai.singlr.session.hooks.HookContext} (and {@link ai.singlr.session.hooks.DefaultHookContext})
 * carries the per-fire session id / turn / model handle / cancellation token so hooks can take
 * their own actions (e.g. call the model again).
 */
package ai.singlr.session.hooks;
