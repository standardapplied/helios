/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class SourceTest {

  @Test
  void rejectsNullUrl() {
    assertThrows(IllegalArgumentException.class, () -> new Source("title", null, List.of()));
  }

  @Test
  void rejectsBlankUrl() {
    assertThrows(IllegalArgumentException.class, () -> new Source("title", "   ", List.of()));
  }

  @Test
  void allowsNullTitle() {
    var source = new Source(null, "https://example.com", List.of());
    assertNull(source.title());
  }

  @Test
  void normalizesNullExcerptsToEmptyList() {
    var source = new Source(null, "https://example.com", null);
    assertEquals(List.of(), source.excerpts());
  }

  @Test
  void rejectsNullExcerptInList() {
    var withNull = Arrays.asList("ok", null);
    assertThrows(
        IllegalArgumentException.class, () -> new Source(null, "https://example.com", withNull));
  }

  @Test
  void copiesExcerptsDefensively() {
    var original = new ArrayList<>(List.of("first"));
    var source = new Source(null, "https://example.com", original);
    original.add("mutated");
    assertEquals(List.of("first"), source.excerpts());
  }

  @Test
  void excerptsAreImmutable() {
    var source = new Source(null, "https://example.com", List.of("a"));
    assertThrows(UnsupportedOperationException.class, () -> source.excerpts().add("b"));
  }

  @Test
  void ofUrlBuildsMinimalSource() {
    var source = Source.ofUrl("https://example.com/x");
    assertNull(source.title());
    assertEquals("https://example.com/x", source.url());
    assertTrue(source.excerpts().isEmpty());
  }

  @Test
  void ofConvenienceCarriesTitleAndExcerpt() {
    var source = Source.of("Example", "https://example.com", "snippet");
    assertEquals("Example", source.title());
    assertEquals("https://example.com", source.url());
    assertEquals(List.of("snippet"), source.excerpts());
  }

  @Test
  void ofWithNullExcerptYieldsEmptyExcerpts() {
    var source = Source.of("Example", "https://example.com", null);
    assertTrue(source.excerpts().isEmpty());
  }

  @Test
  void acceptsNonHttpSchemeForCdiscStyleSources() {
    var source = Source.ofUrl("cdisc-ct://CL.AGEU/YEARS");
    assertEquals("cdisc-ct://CL.AGEU/YEARS", source.url());
  }

  @Test
  void defaultExcerptCapIsExposed() {
    assertEquals(2000, Source.DEFAULT_EXCERPT_MAX_CHARS);
  }
}
