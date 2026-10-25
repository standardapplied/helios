/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.permissions;

import com.standardapplied.helios.core.common.Strings;
import java.util.Objects;
import java.util.Optional;

/**
 * Output of evaluating a tool call against a {@link Permission}. Carries the resolved {@link
 * PermissionEffect}, a human-readable reason for audit and UI surfaces, and the rule that decided.
 *
 * @param effect the resolved decision; non-null
 * @param reason a short audit-ready description; non-null and non-blank
 * @param rule the rule that decided, empty when a mode or category default did; non-null
 */
public record PermissionDecision(
    PermissionEffect effect, String reason, Optional<PermissionRule> rule) {

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
    Objects.requireNonNull(rule, "rule must not be null");
  }

  /**
   * Convenience: an {@link PermissionEffect#ALLOW} decision by a mode or category default.
   *
   * @param reason non-blank reason
   * @return a fresh decision
   */
  public static PermissionDecision allow(String reason) {
    return new PermissionDecision(PermissionEffect.ALLOW, reason, Optional.empty());
  }

  /**
   * Convenience: an {@link PermissionEffect#ASK} decision by a mode or category default.
   *
   * @param reason non-blank reason
   * @return a fresh decision
   */
  public static PermissionDecision ask(String reason) {
    return new PermissionDecision(PermissionEffect.ASK, reason, Optional.empty());
  }

  /**
   * Convenience: a {@link PermissionEffect#DENY} decision by a mode or category default.
   *
   * @param reason non-blank reason
   * @return a fresh decision
   */
  public static PermissionDecision deny(String reason) {
    return new PermissionDecision(PermissionEffect.DENY, reason, Optional.empty());
  }
}
