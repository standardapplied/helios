/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.events;

import com.standardapplied.helios.core.events.HeliosEvent.AfterTurn;
import com.standardapplied.helios.core.events.HeliosEvent.BeforeApiCall;
import com.standardapplied.helios.core.events.HeliosEvent.BeforeCompaction;
import com.standardapplied.helios.core.events.HeliosEvent.IterationCompleted;
import com.standardapplied.helios.core.events.HeliosEvent.IterationStarted;
import com.standardapplied.helios.core.events.HeliosEvent.SessionEnd;
import java.util.Optional;
import java.util.UUID;

/**
 * Encoders for the iteration boundaries and agent-loop hook events. Who the caller is (user and
 * session) is written only in full detail.
 */
final class LoopEventEncoders {

  private LoopEventEncoders() {}

  static void iterationStarted(IterationStarted e, JsonObjectWriter json, boolean full) {
    json.number("iteration", e.iteration()).number("maxIterations", e.maxIterations());
  }

  static void iterationCompleted(IterationCompleted e, JsonObjectWriter json, boolean full) {
    json.number("iteration", e.iteration());
  }

  static void beforeApiCall(BeforeApiCall e, JsonObjectWriter json, boolean full) {
    caller(json, full, e.userId(), e.sessionId());
    json.number("messageCount", e.messages().size()).number("iteration", e.iteration());
  }

  static void afterTurn(AfterTurn e, JsonObjectWriter json, boolean full) {
    caller(json, full, e.userId(), e.sessionId());
    json.number("toolMessageCount", e.toolMessages().size()).number("iteration", e.iteration());
  }

  static void beforeCompaction(BeforeCompaction e, JsonObjectWriter json, boolean full) {
    caller(json, full, e.userId(), e.sessionId());
    json.number("messageCount", e.messages().size());
  }

  static void sessionEnd(SessionEnd e, JsonObjectWriter json, boolean full) {
    caller(json, full, e.userId(), e.sessionId());
    json.string("termination", e.termination().name())
        .number("finalMessageCount", e.finalMessages().size());
  }

  private static void caller(JsonObjectWriter json, boolean full, String userId, UUID sessionId) {
    if (full) {
      json.optionalString("userId", Optional.ofNullable(userId))
          .string("sessionId", sessionId == null ? "" : sessionId.toString());
    }
  }
}
