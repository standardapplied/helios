/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CitationTest {

  @Test
  void ofFactoryMethod() {
    var citation = Citation.of("doc-123", "Referenced content");

    assertEquals("doc-123", citation.sourceId());
    assertEquals("Referenced content", citation.content());
    assertNull(citation.title());
    assertNull(citation.startIndex());
    assertNull(citation.endIndex());
    assertTrue(citation.metadata().isEmpty());
  }

  @Test
  void builderWithAllFields() {
    var metadata = Map.of("page", "42", "section", "intro");
    var citation =
        Citation.newBuilder()
            .withSourceId("doc-456")
            .withTitle("Important Document")
            .withContent("This is the cited text")
            .withStartIndex(100)
            .withEndIndex(150)
            .withMetadata(metadata)
            .build();

    assertEquals("doc-456", citation.sourceId());
    assertEquals("Important Document", citation.title());
    assertEquals("This is the cited text", citation.content());
    assertEquals(100, citation.startIndex());
    assertEquals(150, citation.endIndex());
    assertEquals("42", citation.metadata().get("page"));
    assertEquals("intro", citation.metadata().get("section"));
  }

  @Test
  void builderWithNullMetadata() {
    var citation = Citation.newBuilder().withSourceId("doc-789").withMetadata(null).build();

    assertTrue(citation.metadata().isEmpty());
  }

  @Test
  void builderMinimal() {
    var citation = Citation.newBuilder().withSourceId("doc-001").withContent("Content").build();

    assertEquals("doc-001", citation.sourceId());
    assertEquals("Content", citation.content());
  }

  @Test
  void metadataIsImmutable() {
    var citation =
        Citation.newBuilder().withSourceId("doc-999").withMetadata(Map.of("key", "value")).build();

    assertEquals(1, citation.metadata().size());
  }
}
