/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.standardapplied.helios.core.common.SecretRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class RedactedReasonTest {

  @Test
  void everySecretIsRedactedAndCounted() {
    var secrets = new SecretRegistry();
    secrets.register("TOKEN", "tok-0123456789");
    var reason = RedactedReason.of("key Write(tok-0123456789) denied", secrets);
    assertEquals("key Write(<redacted:TOKEN>) denied", reason.text());
    assertEquals(Map.of("TOKEN", 1), reason.counts());
  }

  @Test
  void aLongerReasonIsCutAtTheBound() {
    var reason = "r".repeat(RedactedReason.MAX_REASON_CHARS + 5);
    assertEquals(
        "r".repeat(RedactedReason.MAX_REASON_CHARS) + "… [5 more chars]",
        RedactedReason.of(reason, new SecretRegistry()).text());
  }
}
