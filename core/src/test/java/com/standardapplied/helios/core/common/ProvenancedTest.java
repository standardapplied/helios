/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProvenancedTest {

  private record Pick(String name, String reason) {}

  @Test
  void rejectsNullOutput() {
    assertThrows(IllegalArgumentException.class, () -> new Provenanced<>(null, List.of()));
  }

  @Test
  void normalizesNullProvenanceToEmptyList() {
    var p = new Provenanced<>(new Pick("a", "b"), null);
    assertEquals(List.of(), p.provenance());
  }

  @Test
  void rejectsNullEntryInProvenance() {
    var withNull = Arrays.asList(FieldProvenance.lowConfidence("name", "reason"), null);
    assertThrows(
        IllegalArgumentException.class, () -> new Provenanced<>(new Pick("a", "b"), withNull));
  }

  @Test
  void copiesProvenanceDefensively() {
    var entries = new ArrayList<FieldProvenance>();
    entries.add(FieldProvenance.lowConfidence("name", "reason"));
    var p = new Provenanced<>(new Pick("a", "b"), entries);
    entries.add(FieldProvenance.lowConfidence("reason", "more"));
    assertEquals(1, p.provenance().size());
  }

  @Test
  void provenanceIsImmutable() {
    var p =
        new Provenanced<>(
            new Pick("a", "b"), List.of(FieldProvenance.lowConfidence("name", "reason")));
    assertThrows(
        UnsupportedOperationException.class,
        () -> p.provenance().add(FieldProvenance.lowConfidence("reason", "more")));
  }

  @Test
  void forFieldFindsMatchingEntry() {
    var byName = FieldProvenance.lowConfidence("name", "alpha");
    var byReason = FieldProvenance.lowConfidence("reason", "beta");
    var p = new Provenanced<>(new Pick("a", "b"), List.of(byName, byReason));
    assertSame(byName, p.forField("name"));
    assertSame(byReason, p.forField("reason"));
  }

  @Test
  void forFieldReturnsNullForUnknown() {
    var p =
        new Provenanced<>(
            new Pick("a", "b"), List.of(FieldProvenance.lowConfidence("name", "alpha")));
    assertNull(p.forField("missing"));
  }

  @Test
  void forFieldReturnsNullForNullArg() {
    var p =
        new Provenanced<>(
            new Pick("a", "b"), List.of(FieldProvenance.lowConfidence("name", "alpha")));
    assertNull(p.forField(null));
  }

  @Test
  void provenanceByFieldIndexesByName() {
    var byName = FieldProvenance.lowConfidence("name", "alpha");
    var byReason = FieldProvenance.lowConfidence("reason", "beta");
    var p = new Provenanced<>(new Pick("a", "b"), List.of(byName, byReason));
    var index = p.provenanceByField();
    assertEquals(2, index.size());
    assertSame(byName, index.get("name"));
    assertSame(byReason, index.get("reason"));
  }

  @Test
  void provenanceByFieldHandlesEmpty() {
    var p = new Provenanced<>(new Pick("a", "b"), List.of());
    assertTrue(p.provenanceByField().isEmpty());
  }

  @Test
  void provenanceByFieldLastWriteWins() {
    var first = new FieldProvenance("name", List.of(), "first", Confidence.LOW);
    var second = new FieldProvenance("name", List.of(), "second", Confidence.LOW);
    var p = new Provenanced<>(new Pick("a", "b"), List.of(first, second));
    var index = p.provenanceByField();
    assertEquals(1, index.size());
    assertSame(second, index.get("name"));
  }

  @Test
  void provenanceByFieldIsImmutable() {
    var p = new Provenanced<>(new Pick("a", "b"), List.of());
    assertThrows(
        UnsupportedOperationException.class,
        () -> p.provenanceByField().put("name", FieldProvenance.lowConfidence("name", "r")));
  }
}
