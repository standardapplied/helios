/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.hooks;

import ai.singlr.core.common.Strings;
import ai.singlr.core.model.Model;
import ai.singlr.core.runtime.CancellationToken;
import java.util.Objects;

/**
 * Concrete {@link HookContext} record. Sessions construct one of these per hook fire, closing over
 * the session's stable references (model, cancellation token) and the per-call values (sessionId,
 * turnIndex).
 *
 * <p>Immutable. Cheap enough to allocate per hook firing rather than caching.
 *
 * @param sessionId the session id
 * @param turnIndex the agent-loop turn index
 * @param cancellation the session's cancellation token
 * @param model the model the loop is driving
 */
public record DefaultHookContext(
    String sessionId, long turnIndex, CancellationToken cancellation, Model model)
    implements HookContext {

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if any argument is null
   * @throws IllegalArgumentException if {@code sessionId} is blank or {@code turnIndex} is negative
   */
  public DefaultHookContext {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    if (Strings.isBlank(sessionId)) {
      throw new IllegalArgumentException("sessionId must not be blank");
    }
    if (turnIndex < 0) {
      throw new IllegalArgumentException("turnIndex must be non-negative, got " + turnIndex);
    }
    Objects.requireNonNull(cancellation, "cancellation must not be null");
    Objects.requireNonNull(model, "model must not be null");
  }
}
