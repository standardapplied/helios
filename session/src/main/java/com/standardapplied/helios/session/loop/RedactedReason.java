/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.common.RedactionResult;
import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.common.Strings;
import java.nio.charset.StandardCharsets;

/**
 * A reason a hook or a policy refused something for, as the session shows it: every registered
 * secret redacted and the text bounded.
 */
final class RedactedReason {

  static final int MAX_REASON_CHARS = 1024;

  private RedactedReason() {}

  /**
   * {@code reason} with every registered secret redacted, cut after {@link #MAX_REASON_CHARS}
   * characters (never inside a surrogate pair) with a note of how much was cut, and the count of
   * each secret redacted.
   */
  static RedactionResult of(String reason, SecretRegistry secrets) {
    var redaction = secrets.redactor().redact(reason);
    var cut = Strings.truncated(redaction.text(), MAX_REASON_CHARS, "… [%d more chars]");
    return new RedactionResult(cut.getBytes(StandardCharsets.UTF_8), redaction.counts());
  }
}
