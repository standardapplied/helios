/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

/**
 * The Permission system — declarative policy that gates every tool call before the loop dispatches
 * it.
 *
 * <p>{@link ai.singlr.session.permissions.Permission} is the immutable policy record: a {@link
 * ai.singlr.session.permissions.PermissionMode} ({@code DEFAULT} / {@code PLAN} / {@code
 * ACCEPT_EDITS} / {@code BYPASS_PERMISSIONS}) plus three lists of {@link
 * ai.singlr.session.permissions.PermissionRule} (allow / ask / deny). Each rule names a tool +
 * optional path glob and maps to a {@link ai.singlr.session.permissions.PermissionEffect}; the
 * {@link ai.singlr.session.permissions.RuleMatcher} translates globs to anchored regexes once at
 * construction.
 *
 * <p>{@link ai.singlr.session.permissions.DefaultPermissionEvaluator} packages the policy as a
 * {@link ai.singlr.session.hooks.PreToolUseHook} at priority {@code 50} (before user hooks). The
 * evaluator translates a call into a {@link ai.singlr.session.permissions.PermissionDecision},
 * which becomes a {@link ai.singlr.session.hooks.HookOutcome}: {@code ALLOW → Continue}, {@code
 * DENY → Block}, {@code ASK → AskUserQuestion} via the session's {@link
 * ai.singlr.session.ask.QuestionGateway} (or fallback {@code Block} when no gateway is wired).
 * Build with {@code DefaultPermissionEvaluator.newBuilder(perm, tools).withQuestionGateway(gw)
 * .build()}.
 *
 * <p>Two curated policies live as static factories on {@code Permission}: {@code
 * defaultInWorkspace()} (reads + memory allowed, writes / edit / execute asked) and {@code
 * planMode()} (reads only, writes denied) — the {@link ai.singlr.session.SessionPresets} use them.
 */
package ai.singlr.session.permissions;
