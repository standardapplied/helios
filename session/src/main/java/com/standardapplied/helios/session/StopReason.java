/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

/**
 * Why a single agent-loop turn ended.
 *
 * <p>Distinct from {@code ai.singlr.core.model.FinishReason}: {@code FinishReason} describes why a
 * single model API call finished; {@code StopReason} describes why the agent loop's turn (model
 * call plus any tool dispatch plus hook firing) ended. Distinct from the loop-terminating reason,
 * which is carried by {@code ResultMessage} subtypes.
 */
public enum StopReason {
  /** Model finished without tool use; the loop may stop after a {@code PreStopHook} check. */
  END_TURN,

  /** Model emitted tool calls; the turn ends, tools dispatch, the loop continues. */
  TOOL_USE,

  /** Model hit its per-call output token cap. */
  MAX_TOKENS,

  /** {@code AgentSession.interrupt(...)} fired during this turn. */
  INTERRUPTED,

  /** Model declined via the provider's content/safety filter. */
  REFUSAL,

  /** Provider error, hook-induced abort, or other unrecoverable condition during this turn. */
  ERROR
}
