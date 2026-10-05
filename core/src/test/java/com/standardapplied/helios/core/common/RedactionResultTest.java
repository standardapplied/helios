/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RedactionResultTest {

  private static RedactionResult result(Map<String, Integer> counts) {
    return new RedactionResult(new byte[0], counts);
  }

  private static Map<String, Integer> ordered(Object... namesAndCounts) {
    var counts = new LinkedHashMap<String, Integer>();
    for (var i = 0; i < namesAndCounts.length; i += 2) {
      counts.put((String) namesAndCounts[i], (Integer) namesAndCounts[i + 1]);
    }
    return counts;
  }

  @Test
  void mergeCountsOfTwoCleanResultsIsTheEmptyMap() {
    assertSame(Map.of(), result(Map.of()).mergeCounts(result(Map.of())));
  }

  @Test
  void mergeCountsSumsSharedNamesInEncounterOrder() {
    var merged = result(ordered("B", 1, "A", 2)).mergeCounts(result(ordered("C", 4, "A", 3)));

    assertEquals(
        List.of(Map.entry("B", 1), Map.entry("A", 5), Map.entry("C", 4)),
        List.copyOf(merged.entrySet()));
  }

  @Test
  void mergeCountsKeepsOneSidedCounts() {
    assertEquals(Map.of("A", 1), result(Map.of()).mergeCounts(result(Map.of("A", 1))));
    assertEquals(Map.of("A", 1), result(Map.of("A", 1)).mergeCounts(result(Map.of())));
  }

  @Test
  void mergeCountsRejectsANullCountOnEitherSide() {
    var withNull = new HashMap<String, Integer>();
    withNull.put("A", null);

    assertThrows(
        NullPointerException.class, () -> result(withNull).mergeCounts(result(Map.of("B", 1))));
    assertThrows(
        NullPointerException.class, () -> result(Map.of("B", 1)).mergeCounts(result(withNull)));
  }

  @Test
  void mergedCountsAreUnmodifiable() {
    var merged = result(Map.of("A", 1)).mergeCounts(result(Map.of()));

    assertThrows(UnsupportedOperationException.class, () -> merged.put("B", 1));
  }
}
