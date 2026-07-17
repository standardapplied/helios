/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

/**
 * Singular Agentic Framework — testing support.
 *
 * <p>Deterministic test doubles for Helios consumers. {@code ScriptedModel} replays a fixed script
 * of turns (text, tool calls, structured JSON) through the real {@code Model} contract so agent
 * tests and CI evals run without a live provider.
 */
module ai.singlr.testing {
  requires ai.singlr.core;
  requires tools.jackson.databind;

  exports ai.singlr.testing;
}
