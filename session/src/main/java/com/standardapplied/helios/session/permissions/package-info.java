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
 * / {@code PLAN} / {@code ACCEPT_EDITS} / {@code BYPASS_PERMISSIONS}) plus three lists of {@link
 * com.standardapplied.helios.session.permissions.PermissionRule} (allow / ask / deny). Each rule
 * names a tool + optional path glob and maps to a {@link
 * com.standardapplied.helios.session.permissions.PermissionEffect}; the {@link
 * com.standardapplied.helios.session.permissions.RuleMatcher} translates globs to anchored regexes
 * once at construction.
 *
 * <p>{@link com.standardapplied.helios.session.permissions.DefaultPermissionEvaluator} enforces the
 * policy as a fixed stage of tool dispatch, after every {@code PreToolUse} hook, so no hook can
 * reorder, skip or follow it. It turns the final call into a {@link
 * com.standardapplied.helios.session.permissions.PermissionDecision}: {@code ALLOW} runs the call,
 * {@code DENY} refuses it, and {@code ASK} refers it to a {@link
 * com.standardapplied.helios.session.permissions.ToolApprover}, by default {@link
 * com.standardapplied.helios.session.permissions.ToolApprover#askingUser} over the session's {@link
 * com.standardapplied.helios.session.ask.QuestionGateway}. With no approver, ASK refuses. Build
 * with {@code DefaultPermissionEvaluator.newBuilder(perm, tools).withApprover(approver,
 * argumentWriter).build()}, the writer rendering a call's arguments as JSON for the approver.
 *
 * <p>Two curated policies live as static factories on {@code Permission}: {@code
 * defaultInWorkspace()} (reads + memory allowed, writes / edit / execute asked) and {@code
 * planMode()} (reads only, writes denied) — the {@link
 * com.standardapplied.helios.session.SessionPresets} use them.
 */
package com.standardapplied.helios.session.permissions;
