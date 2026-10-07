/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

/**
 * Helios — testing support.
 *
 * <p>Deterministic test doubles for Helios consumers. {@code ScriptedModel} replays a fixed script
 * of turns (text, tool calls, structured JSON, whole responses, chunk streams) through the real
 * {@code Model} contract so agent tests and CI evals run without a live provider; {@code
 * ModelStreams} builds the chunk streams a stream turn answers with.
 */
module com.standardapplied.helios.testing {
  requires com.standardapplied.helios.core;
  requires tools.jackson.databind;

  exports com.standardapplied.helios.testing;
}
