/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

/**
 * The Permission system — declarative policy that gates every tool call before the loop dispatches
 * it.
 *
 * <p>{@link com.standardapplied.helios.session.permissions.Permission} is the immutable policy
 * record: a {@link com.standardapplied.helios.session.permissions.PermissionMode} ({@code DEFAULT}
 * / {@code PLAN} / {@code ACCEPT_EDITS} / {@code BYPASS_PERMISSIONS} / {@code LOCKED_DOWN}) plus
 * three lists of {@link com.standardapplied.helios.session.permissions.PermissionRule} (allow / ask
 * / deny). Each rule names a tool + optional glob; the list it sits in is its effect, and the most
 * restrictive matching rule decides (deny, then ask, then allow), else the mode's default for the
 * tool's category. A session with no policy set gets {@code Permission.empty(DEFAULT)}.
 *
 * <p>The session enforces the policy as a fixed stage of tool dispatch, after every {@code
 * PreToolUse} hook, so no hook can reorder, skip or follow it. It turns the final call into a
 * {@link com.standardapplied.helios.session.permissions.PermissionDecision}: {@code ALLOW} runs the
 * call, {@code DENY} refuses it, and {@code ASK} asks the user through a {@code QuestionAsked}
 * event answered with {@code AgentSession.answer}. To automate approvals, answer that question, or
 * decide the call in a {@code PreToolUseHook}. {@link
 * com.standardapplied.helios.session.permissions.Permission#evaluate} is the same decision for one
 * key, pure, so a host can dry-run its policy in a unit test.
 *
 * <p>Two curated policies live as static factories on {@code Permission}: {@code
 * defaultInWorkspace()} ({@code DEFAULT} plus memory writes allowed) and {@code planMode()} (reads
 * only, writes denied) — the {@link com.standardapplied.helios.session.SessionPresets} use them.
 */
package com.standardapplied.helios.session.permissions;
