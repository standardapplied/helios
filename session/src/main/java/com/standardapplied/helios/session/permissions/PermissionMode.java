/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.permissions;

/**
 * Coarse policy lever on top of the per-tool {@link PermissionRule} list.
 *
 * <p>Per spec §12.2:
 *
 * <ul>
 *   <li>{@link #DEFAULT} — ask on writes / execution, allow reads. The standard interactive mode.
 *   <li>{@link #ACCEPT_EDITS} — allow workspace writes silently. Useful for batch edit flows where
 *       the user has pre-authorised the session.
 *   <li>{@link #BYPASS_PERMISSIONS} — allow everything. The sandbox is the real wall.
 *   <li>{@link #PLAN} — read-only mode. No writes, no execution; useful for an outline pass.
 *   <li>{@link #LOCKED_DOWN} — deny everything not explicitly allowed. The "sandbox is the world"
 *       mode used by CodeAct-style presets where every capability except code execution should
 *       refuse by default. Category defaults all flip to deny; only explicit {@code allow} rules
 *       open holes.
 * </ul>
 */
public enum PermissionMode {
  /** Ask on writes / execution, allow reads. */
  DEFAULT,
  /** Allow workspace writes silently. */
  ACCEPT_EDITS,
  /** Allow everything. */
  BYPASS_PERMISSIONS,
  /** Read-only — no writes, no execution. */
  PLAN,
  /** Deny everything by default — only explicit {@code allow} rules open holes. */
  LOCKED_DOWN
}
