/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProvenanceValidatorTest {

  @Test
  void defaultPassesLowWithoutSources() {
    var entry = FieldProvenance.lowConfidence("name", "best guess");
    assertTrue(ProvenanceValidator.DEFAULT.validate(entry).ok());
  }

  @Test
  void defaultRejectsMediumWithoutSources() {
    var entry = new FieldProvenance("name", List.of(), "reason", Confidence.MEDIUM);
    var result = ProvenanceValidator.DEFAULT.validate(entry);
    assertFalse(result.ok());
    assertTrue(result.message().contains("name"));
    assertTrue(result.message().contains("MEDIUM"));
    assertTrue(result.message().contains("requires at least one source"));
  }

  @Test
  void defaultRejectsHighWithoutSources() {
    var entry = new FieldProvenance("name", List.of(), "reason", Confidence.HIGH);
    var result = ProvenanceValidator.DEFAULT.validate(entry);
    assertFalse(result.ok());
    assertTrue(result.message().contains("HIGH"));
  }

  @Test
  void defaultPassesMediumWithSource() {
    var entry =
        new FieldProvenance(
            "name", List.of(Source.ofUrl("https://x.com")), "reason", Confidence.MEDIUM);
    assertTrue(ProvenanceValidator.DEFAULT.validate(entry).ok());
  }

  @Test
  void defaultPassesHighWithSource() {
    var entry =
        new FieldProvenance(
            "name", List.of(Source.ofUrl("https://x.com")), "reason", Confidence.HIGH);
    assertTrue(ProvenanceValidator.DEFAULT.validate(entry).ok());
  }

  @Test
  void excerptLengthCapPassesWithinLimit() {
    var validator = ProvenanceValidator.excerptLengthCap(100);
    var entry =
        new FieldProvenance(
            "name", List.of(Source.of("t", "https://x.com", "short")), "r", Confidence.HIGH);
    assertTrue(validator.validate(entry).ok());
  }

  @Test
  void excerptLengthCapRejectsOverLimit() {
    var validator = ProvenanceValidator.excerptLengthCap(10);
    var entry =
        new FieldProvenance(
            "name",
            List.of(Source.of("t", "https://x.com", "this is more than ten chars")),
            "r",
            Confidence.HIGH);
    var result = validator.validate(entry);
    assertFalse(result.ok());
    assertTrue(result.message().contains("excerpt exceeds 10 characters"));
  }

  @Test
  void excerptLengthCapPassesEntryWithoutExcerpts() {
    var validator = ProvenanceValidator.excerptLengthCap(10);
    var entry =
        new FieldProvenance("name", List.of(Source.ofUrl("https://x.com")), "r", Confidence.MEDIUM);
    assertTrue(validator.validate(entry).ok());
  }

  @Test
  void excerptLengthCapRejectsNonPositiveLimit() {
    assertThrows(IllegalArgumentException.class, () -> ProvenanceValidator.excerptLengthCap(0));
    assertThrows(IllegalArgumentException.class, () -> ProvenanceValidator.excerptLengthCap(-1));
  }

  @Test
  void andThenChainsTwoValidatorsOnSuccess() {
    ProvenanceValidator alwaysOk = e -> ValidationResult.success();
    ProvenanceValidator alwaysFail = e -> ValidationResult.failure("second failed");
    var chain = alwaysOk.andThen(alwaysFail);
    var entry = FieldProvenance.lowConfidence("name", "r");
    assertEquals("second failed", chain.validate(entry).message());
  }

  @Test
  void andThenShortCircuitsOnFirstFailure() {
    ProvenanceValidator firstFail = e -> ValidationResult.failure("first failed");
    ProvenanceValidator neverRuns =
        e -> {
          throw new AssertionError("must not run");
        };
    var chain = firstFail.andThen(neverRuns);
    var entry = FieldProvenance.lowConfidence("name", "r");
    assertEquals("first failed", chain.validate(entry).message());
  }

  @Test
  void andThenRejectsNullNext() {
    ProvenanceValidator any = e -> ValidationResult.success();
    assertThrows(IllegalArgumentException.class, () -> any.andThen(null));
  }
}
