/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.tools;

import ai.singlr.core.common.Strings;
import java.util.Objects;

/**
 * Read-only context the agent loop hands to a {@link ToolBinding#visibility() visibility predicate}
 * when deciding whether to advertise a tool to the model for a given turn.
 *
 * <p>Visibility is decided per-turn; the same tool may appear in turn N's tool list and disappear
 * in turn N+1 (e.g. a {@code Submit} tool that vanishes after submission, or a destination-specific
 * {@code Send} tool that only appears once the model has chosen a recipient).
 *
 * <p>Phase 2 ships the minimum field set: session id and the current turn index. Subsequent phases
 * extend the contract — Phase 5 adds {@code executionProvider()}, Phase 7 adds {@code audit()}.
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
