/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.permissions;

/**
 * The effect of a {@link PermissionDecision}: the effect of the {@link Permission} list whose rule
 * decided, or of the mode's default for the tool's category.
 *
 * <ul>
 *   <li>{@link #ALLOW} — proceed with the call.
 *   <li>{@link #ASK} — refer the call to the session's {@link ToolApprover}; with none, refuse it.
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
