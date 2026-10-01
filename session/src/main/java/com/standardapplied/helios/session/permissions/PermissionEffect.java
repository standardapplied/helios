/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.permissions;

/**
 * Decision a {@link PermissionRule} carries when it matches.
 *
 * <ul>
 *   <li>{@link #ALLOW} — proceed with the call.
 *   <li>{@link #ASK} — surface the call for user confirmation (Phase 2 part 5 wires the question
 *       handler; until then, treated as {@link #DENY}).
 *   <li>{@link #DENY} — refuse the call with the rule's reason.
 * </ul>
 */
public enum PermissionEffect {
  /** Permit the call. */
  ALLOW,
  /** Surface the call to the user for confirmation. */
  ASK,
  /** Refuse the call. */
  DENY
}
