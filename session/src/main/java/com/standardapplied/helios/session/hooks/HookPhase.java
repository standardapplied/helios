/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.hooks;

/**
 * The phase a deciding hook was bound to, as {@link
 * com.standardapplied.helios.session.QueryEvent.HookFired HookFired} names it: one constant per
 * phase whose hooks return a {@link HookOutcome}. The observe-only {@link OnStreamEventHook} never
 * decides, so it has none.
 */
public enum HookPhase {
  /** {@link OnUserMessageHook}. */
  ON_USER_MESSAGE,
  /** {@link PreModelTurnHook}. */
  PRE_MODEL_TURN,
  /** {@link PostModelTurnHook}. */
  POST_MODEL_TURN,
  /** {@link PreStopHook}. */
  PRE_STOP,
  /** {@link PreToolUseHook}. */
  PRE_TOOL_USE,
  /** {@link PostToolUseHook}. */
  POST_TOOL_USE
}
