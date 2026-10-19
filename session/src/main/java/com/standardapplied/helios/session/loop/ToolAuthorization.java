/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.session.permissions.PermissionDecision;
import com.standardapplied.helios.session.permissions.PermissionEffect;
import java.util.Optional;
import java.util.function.Function;

/**
 * The mandatory permission stage of a tool call, between the {@code PreToolUse} hooks and dispatch:
 * it decides the exact call that runs, and it fails closed, so only an {@link
 * PermissionEffect#ALLOW} decision admits the call. A refusal's reason, like every reason the round
 * refuses a call with, is a diagnostic: redacted through the session's secrets and bounded.
 */
final class ToolAuthorization {

  /** The name a refusal by the permission policy carries as its {@code by}. */
  static final String BY = "Permission";

  static final int MAX_REASON_CHARS = 1024;

  private ToolAuthorization() {}

  /**
   * Why {@code call} may not run, or empty when {@code authorization} allows it.
   *
   * @param authorization the session's permission check
   * @param call the final call
   * @return the refusal reason, unredacted
   */
  static Optional<String> refusal(
      Function<ToolCall, PermissionDecision> authorization, ToolCall call) {
    PermissionDecision decision;
    try {
      decision = authorization.apply(call);
    } catch (RuntimeException e) {
      return Optional.of(
          "permission check of "
              + call.name()
              + " threw "
              + e.getClass().getName()
              + ": "
              + e.getMessage());
    }
    if (decision == null) {
      return Optional.of("no permission decision for " + call.name());
    }
    return decision.effect() == PermissionEffect.ALLOW
        ? Optional.empty()
        : Optional.of(decision.reason());
  }

  /**
   * {@code reason} with every registered secret redacted, cut after {@link #MAX_REASON_CHARS}
   * characters (never inside a surrogate pair) with a note of how much was cut.
   */
  static String diagnostic(SecretRegistry secrets, String reason) {
    var redacted = secrets.redactor().redact(reason).text();
    if (redacted.length() <= MAX_REASON_CHARS) {
      return redacted;
    }
    var end = redacted.offsetByCodePoints(0, redacted.codePointCount(0, MAX_REASON_CHARS));
    return redacted.substring(0, end) + "… [" + (redacted.length() - end) + " more chars]";
  }
}
