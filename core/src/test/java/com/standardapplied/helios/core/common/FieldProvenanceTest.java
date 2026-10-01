/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class FieldProvenanceTest {

  @Test
  void rejectsBlankField() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new FieldProvenance("", List.of(), "reason", Confidence.LOW));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FieldProvenance(null, List.of(), "reason", Confidence.LOW));
  }

  @Test
  void rejectsBlankReasoning() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new FieldProvenance("name", List.of(), "", Confidence.LOW));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FieldProvenance("name", List.of(), null, Confidence.LOW));
  }

  @Test
  void rejectsNullConfidence() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new FieldProvenance("name", List.of(), "reason", null));
  }

  @Test
  void rejectsNullSourceInList() {
    var withNull = Arrays.asList(Source.ofUrl("https://example.com"), null);
    assertThrows(
        IllegalArgumentException.class,
        () -> new FieldProvenance("name", withNull, "reason", Confidence.LOW));
  }

  @Test
  void normalizesNullSourcesToEmptyList() {
    var entry = new FieldProvenance("name", null, "reason", Confidence.LOW);
    assertEquals(List.of(), entry.sources());
  }

  @Test
  void copiesSourcesDefensively() {
    var sources = new ArrayList<Source>();
    sources.add(Source.ofUrl("https://example.com"));
    var entry = new FieldProvenance("name", sources, "reason", Confidence.MEDIUM);
    sources.add(Source.ofUrl("https://other.com"));
    assertEquals(1, entry.sources().size());
  }

  @Test
  void sourcesAreImmutable() {
    var entry =
        new FieldProvenance(
            "name", List.of(Source.ofUrl("https://example.com")), "reason", Confidence.MEDIUM);
    assertThrows(
        UnsupportedOperationException.class,
        () -> entry.sources().add(Source.ofUrl("https://other.com")));
  }

  @Test
  void lowConfidenceFactoryProducesLowEntry() {
    var entry = FieldProvenance.lowConfidence("name", "best guess");
    assertEquals("name", entry.field());
    assertEquals(Confidence.LOW, entry.confidence());
    assertEquals("best guess", entry.reasoning());
    assertTrue(entry.sources().isEmpty());
  }
}
