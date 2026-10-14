/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import com.standardapplied.helios.core.common.Strings;

/**
 * The verified identity a request acts as: a {@code subject} within a {@code tenant}. A session is
 * owned by the principal that created it, and only an equal principal (same tenant and same
 * subject) can reach it; a user of one tenant never reaches another tenant's session, whatever its
 * subject.
 *
 * <p>Each part is non-blank, at most {@value #MAX_LENGTH} characters and free of control
 * characters, so a principal is always safe to log.
 *
 * @param tenant the tenant the subject belongs to
 * @param subject the authenticated user or service within the tenant
 */
public record Principal(String tenant, String subject) {

  /** Longest accepted tenant or subject, in characters. */
  public static final int MAX_LENGTH = 256;

  /**
   * Validate both parts.
   *
   * @throws IllegalArgumentException if either part is null, blank, longer than {@value
   *     #MAX_LENGTH} characters or holds a control character
   */
  public Principal {
    require("tenant", tenant);
    require("subject", subject);
  }

  /**
   * Whether {@code part} is acceptable as a tenant or subject.
   *
   * @param part the candidate; may be null
   * @return {@code true} when the part is non-blank, bounded and free of control characters
   */
  static boolean isValidPart(String part) {
    return !Strings.isBlank(part)
        && part.length() <= MAX_LENGTH
        && part.chars().noneMatch(Character::isISOControl);
  }

  private static void require(String name, String part) {
    if (!isValidPart(part)) {
      throw new IllegalArgumentException(
          name
              + " must be non-blank, at most "
              + MAX_LENGTH
              + " characters and free of control characters");
    }
  }
}
