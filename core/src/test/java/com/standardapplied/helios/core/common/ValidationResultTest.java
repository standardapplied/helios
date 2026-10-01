/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ValidationResultTest {

  @Test
  void successHasNoMessage() {
    var ok = ValidationResult.success();
    assertTrue(ok.ok());
    assertNull(ok.message());
  }

  @Test
  void successIsCachedSingleton() {
    assertSame(ValidationResult.success(), ValidationResult.success());
  }

  @Test
  void failureCarriesMessage() {
    var bad = ValidationResult.failure("boom");
    assertFalse(bad.ok());
    assertEquals("boom", bad.message());
  }

  @Test
  void failureRequiresNonBlankMessage() {
    assertThrows(IllegalArgumentException.class, () -> ValidationResult.failure(null));
    assertThrows(IllegalArgumentException.class, () -> ValidationResult.failure(""));
    assertThrows(IllegalArgumentException.class, () -> ValidationResult.failure("   "));
  }

  @Test
  void successWithMessageIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> new ValidationResult(true, "stray"));
  }

  @Test
  void failureWithBlankMessageIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> new ValidationResult(false, null));
    assertThrows(IllegalArgumentException.class, () -> new ValidationResult(false, ""));
  }
}
