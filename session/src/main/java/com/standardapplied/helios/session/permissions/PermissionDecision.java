/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.permissions;

import ai.singlr.core.common.Strings;
import java.util.Objects;

/**
 * Output of evaluating a tool call against a {@link Permission}. Carries the resolved {@link
 * PermissionEffect} plus a human-readable reason for audit and UI surfaces.
 *
 * @param effect the resolved decision; non-null
 * @param reason a short audit-ready description; non-null and non-blank
 */
public record PermissionDecision(PermissionEffect effect, String reason) {

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if any argument is null
   * @throws IllegalArgumentException if {@code reason} is blank
   */
  public PermissionDecision {
    Objects.requireNonNull(effect, "effect must not be null");
    Objects.requireNonNull(reason, "reason must not be null");
    if (Strings.isBlank(reason)) {
      throw new IllegalArgumentException("reason must not be blank");
    }
  }

  /**
   * Convenience: an {@link PermissionEffect#ALLOW} decision with the given reason.
   *
   * @param reason non-blank reason
   * @return a fresh decision
   */
  public static PermissionDecision allow(String reason) {
    return new PermissionDecision(PermissionEffect.ALLOW, reason);
  }

  /**
   * Convenience: an {@link PermissionEffect#ASK} decision.
   *
   * @param reason non-blank reason
   * @return a fresh decision
   */
  public static PermissionDecision ask(String reason) {
    return new PermissionDecision(PermissionEffect.ASK, reason);
  }

  /**
   * Convenience: a {@link PermissionEffect#DENY} decision.
   *
   * @param reason non-blank reason
   * @return a fresh decision
   */
  public static PermissionDecision deny(String reason) {
    return new PermissionDecision(PermissionEffect.DENY, reason);
  }
}
