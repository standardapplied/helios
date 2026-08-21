/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SubmitValidatorTest {

  private record Pick(String synthesis) {}

  @Test
  void simpleSuccess() {
    SubmitValidator<Pick> v =
        p ->
            p.synthesis().length() >= 10
                ? ValidationResult.success()
                : ValidationResult.failure("too short");
    assertTrue(v.validate(new Pick("a substantive synthesis here")).ok());
  }

  @Test
  void simpleFailureCarriesMessage() {
    SubmitValidator<Pick> v =
        p ->
            p.synthesis().length() >= 10
                ? ValidationResult.success()
                : ValidationResult.failure("too short");
    var result = v.validate(new Pick("nope"));
    assertFalse(result.ok());
    assertEquals("too short", result.message());
  }

  @Test
  void andThenChainsOnSuccess() {
    SubmitValidator<Pick> first = p -> ValidationResult.success();
    SubmitValidator<Pick> second = p -> ValidationResult.failure("second failed");
    var chain = first.andThen(second);
    assertEquals("second failed", chain.validate(new Pick("x")).message());
  }

  @Test
  void andThenShortCircuitsOnFirstFailure() {
    SubmitValidator<Pick> first = p -> ValidationResult.failure("first failed");
    SubmitValidator<Pick> neverRuns =
        p -> {
          throw new AssertionError("must not run");
        };
    var chain = first.andThen(neverRuns);
    assertEquals("first failed", chain.validate(new Pick("x")).message());
  }

  @Test
  void validateSafelyPassesThroughVerdict() {
    SubmitValidator<Pick> v = p -> ValidationResult.failure("too short");
    assertEquals("too short", v.validateSafely(new Pick("x")).message());
    SubmitValidator<Pick> ok = p -> ValidationResult.success();
    assertTrue(ok.validateSafely(new Pick("x")).ok());
  }

  @Test
  void validateSafelyConvertsThrowToFailure() {
    SubmitValidator<Pick> noisy =
        p -> {
          throw new IllegalStateException("operator bug");
        };
    var result = noisy.validateSafely(new Pick("x"));
    assertFalse(result.ok());
    assertEquals("submit validator threw: operator bug", result.message());
  }

  @Test
  void validateSafelyConvertsNullToFailure() {
    SubmitValidator<Pick> nullish = p -> null;
    var result = nullish.validateSafely(new Pick("x"));
    assertFalse(result.ok());
    assertEquals("submit validator returned null", result.message());
  }

  @Test
  void andThenRejectsNullNext() {
    SubmitValidator<Pick> any = p -> ValidationResult.success();
    assertThrows(IllegalArgumentException.class, () -> any.andThen(null));
  }
}
