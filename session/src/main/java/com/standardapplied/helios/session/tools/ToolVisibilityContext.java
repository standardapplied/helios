/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.tools;

import com.standardapplied.helios.core.common.Strings;
import java.util.Objects;

/**
 * Read-only context the agent loop hands to a {@link ToolBinding#visibility() visibility predicate}
 * when deciding whether to advertise a tool to the model for a given turn.
 *
 * <p>Visibility is decided per-turn; the same tool may appear in turn N's tool list and disappear
 * in turn N+1 (e.g. a {@code Submit} tool that vanishes after submission, or a destination-specific
 * {@code Send} tool that only appears once the model has chosen a recipient).
 *
 * <p>Visibility decides what a turn advertises, not what may run: a hidden tool the model names
 * still runs. To refuse a call, use a deny rule or a {@code PreToolUse} hook's {@code Block}.
 *
 * <p>It carries the session id and the current turn index.
 *
 * <h2>Thread-safety</h2>
 *
 * Immutable.
 *
 * @param sessionId the session id; non-blank
 * @param turnIndex the turn index for which visibility is being evaluated; non-negative
 */
public record ToolVisibilityContext(String sessionId, long turnIndex) {

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if {@code sessionId} is null
   * @throws IllegalArgumentException if {@code sessionId} is blank or {@code turnIndex} is negative
   */
  public ToolVisibilityContext {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    if (Strings.isBlank(sessionId)) {
      throw new IllegalArgumentException("sessionId must not be blank");
    }
    if (turnIndex < 0) {
      throw new IllegalArgumentException("turnIndex must be non-negative, got " + turnIndex);
    }
  }
}
