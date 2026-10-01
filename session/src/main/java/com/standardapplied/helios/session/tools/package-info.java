/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

/**
 * The tool-registration surface — how a session declares which {@link ai.singlr.core.tool.Tool}s
 * the model can call, with the per-tool metadata the loop, hooks, and permission system need.
 *
 * <p>{@link ai.singlr.session.tools.ToolRegistry} is the immutable, name-keyed bag of {@link
 * ai.singlr.session.tools.ToolBinding}s the session advertises. Each binding wraps one {@code Tool}
 * with:
 *
 * <ul>
 *   <li>a {@link ai.singlr.session.tools.ToolCategory} ({@code READ} / {@code SEARCH} / {@code
 *       WRITE} / {@code EXECUTION} / {@code NETWORK} / {@code CONTROL} / {@code DELEGATION}) —
 *       drives the per-category concurrency caps in {@link ai.singlr.session.loop.ToolDispatch} and
 *       the default-by-category branch of the permission evaluator;
 *   <li>an optional {@code permissionKey} extractor that derives a {@link
 *       ai.singlr.session.tools.ToolPermissionKey} from the call's arguments — lets glob rules like
 *       {@code "Edit:./src/**"} fire against the path arg of an edit call;
 *   <li>an optional {@code visibility} predicate keyed on a {@link
 *       ai.singlr.session.tools.ToolVisibilityContext}, so a tool can be hidden from the model on
 *       some turns (e.g. only expose {@code Edit} after the user has confirmed plan mode).
 * </ul>
 *
 * <p>{@link ai.singlr.session.tools.ToolArgs} is the shared helper for safely coercing the
 * loosely-typed {@code Map<String, Object>} the model emits into typed arguments inside a tool
 * implementation.
 */
package ai.singlr.session.tools;
