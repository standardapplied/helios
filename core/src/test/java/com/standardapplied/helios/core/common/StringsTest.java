/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StringsTest {

  @Test
  void renderSimple() {
    var template = "Hello, {name}!";
    var result = Strings.render(template, Map.of("name", "World"));

    assertEquals("Hello, World!", result);
  }

  @Test
  void renderMultiplePlaceholders() {
    var template = "{greeting}, {name}! Welcome to {place}.";
    var result =
        Strings.render(
            template, Map.of("greeting", "Hello", "name", "Alice", "place", "Wonderland"));

    assertEquals("Hello, Alice! Welcome to Wonderland.", result);
  }

  @Test
  void renderWithMissingPlaceholder() {
    var template = "Hello, {name}! Your score is {score}.";
    var result = Strings.render(template, Map.of("name", "Bob"));

    assertEquals("Hello, Bob! Your score is {score}.", result);
  }

  @Test
  void renderNullTemplate() {
    assertNull(Strings.render(null, Map.of("name", "test")));
  }

  @Test
  void renderEmptyValues() {
    var template = "Hello, {name}!";
    assertEquals(template, Strings.render(template, Map.of()));
  }

  @Test
  void renderSafeWithAllowedKeys() {
    var template = "{allowed} and {forbidden}";
    var result =
        Strings.renderSafe(
            template, Map.of("allowed", "yes", "forbidden", "no"), Set.of("allowed"));

    assertEquals("yes and {forbidden}", result);
  }

  @Test
  void isEmpty() {
    assertTrue(Strings.isEmpty(null));
    assertTrue(Strings.isEmpty(""));
    assertFalse(Strings.isEmpty(" "));
    assertFalse(Strings.isEmpty("text"));
  }

  @Test
  void isBlank() {
    assertTrue(Strings.isBlank(null));
    assertTrue(Strings.isBlank(""));
    assertTrue(Strings.isBlank("   "));
    assertTrue(Strings.isBlank("\t\n"));
    assertFalse(Strings.isBlank("text"));
  }

  @Test
  void orDefault() {
    assertEquals("default", Strings.orDefault(null, "default"));
    assertEquals("default", Strings.orDefault("", "default"));
    assertEquals("default", Strings.orDefault("  ", "default"));
    assertEquals("value", Strings.orDefault("value", "default"));
  }

  @Test
  void renderSafeWithNullTemplate() {
    assertNull(Strings.renderSafe(null, Map.of("key", "value"), Set.of("key")));
  }

  @Test
  void renderSafeWithNullValues() {
    var template = "Hello {name}";
    assertEquals(template, Strings.renderSafe(template, null, Set.of("name")));
  }

  @Test
  void renderSafeWithEmptyValues() {
    var template = "Hello {name}";
    assertEquals(template, Strings.renderSafe(template, Map.of(), Set.of("name")));
  }

  @Test
  void renderWithNullValues() {
    var template = "Hello {name}";
    assertEquals(template, Strings.render(template, null));
  }

  @Test
  void aTextAtTheBoundIsKeptWhole() {
    var whole = "r".repeat(8);
    assertSame(whole, Strings.truncated(whole, 8, "… [%d more chars]"));
  }

  @Test
  void aLongerTextIsCutAtTheBoundWithItsNoteOfWhatWasCut() {
    assertEquals("r".repeat(8) + "\ncut 5", Strings.truncated("r".repeat(13), 8, "\ncut %d"));
  }

  @Test
  void aCutAcrossASurrogatePairKeepsAtMostTheBound() {
    assertEquals("rrr… [6 more chars]", Strings.truncated("rrr😀tail", 4, "… [%d more chars]"));
    assertEquals("rrr… [2 more chars]", Strings.truncated("rrr😀", 4, "… [%d more chars]"));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void aBoundThatKeepsNothingIsRejected(int maxChars) {
    var ex =
        assertThrows(IllegalArgumentException.class, () -> Strings.truncated("r", maxChars, "%d"));
    assertEquals("maxChars must be positive: " + maxChars, ex.getMessage());
  }
}
