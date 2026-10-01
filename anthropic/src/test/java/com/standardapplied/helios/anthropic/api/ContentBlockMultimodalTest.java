/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.anthropic.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

final class ContentBlockMultimodalTest {

  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  void imageBlockSerialisesWithBase64Source() throws Exception {
    var block = ContentBlock.image("image/png", "aGVsbG8=");
    assertTrue(block.hasTypeImage());
    assertFalse(block.hasTypeDocument());
    assertEquals("base64", block.source().type());
    assertEquals("image/png", block.source().mediaType());
    assertEquals("aGVsbG8=", block.source().data());
    var json = mapper.writeValueAsString(block);
    assertTrue(json.contains("\"type\":\"image\""), json);
    assertTrue(json.contains("\"media_type\":\"image/png\""), json);
    assertTrue(json.contains("\"data\":\"aGVsbG8=\""), json);
  }

  @Test
  void documentBlockSerialisesWithBase64Source() throws Exception {
    var block = ContentBlock.document("application/pdf", "JVBERi0=");
    assertTrue(block.hasTypeDocument());
    assertFalse(block.hasTypeImage());
    assertEquals("application/pdf", block.source().mediaType());
    var json = mapper.writeValueAsString(block);
    assertTrue(json.contains("\"type\":\"document\""), json);
    assertTrue(json.contains("\"media_type\":\"application/pdf\""), json);
  }

  @Test
  void textBlockRemainsUnchanged() throws Exception {
    var block = ContentBlock.text("hello");
    var json = mapper.writeValueAsString(block);
    assertEquals("{\"type\":\"text\",\"text\":\"hello\"}", json);
  }

  @Test
  void sourceFactoryProducesBase64Kind() {
    var src = ContentBlock.Source.base64("image/jpeg", "xx");
    assertEquals("base64", src.type());
    assertEquals("image/jpeg", src.mediaType());
    assertEquals("xx", src.data());
  }
}
