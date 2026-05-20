/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GeminiModelIdTest {

  @Test
  void enumHasCorrectId() {
    assertEquals("gemini-3-flash-preview", GeminiModelId.GEMINI_3_FLASH_PREVIEW.id());
    assertEquals("gemini-3.1-pro-preview", GeminiModelId.GEMINI_3_1_PRO_PREVIEW.id());
    assertEquals("gemini-3.1-flash-lite", GeminiModelId.GEMINI_3_1_FLASH_LITE.id());
    assertEquals("gemini-3.5-flash", GeminiModelId.GEMINI_3_5_FLASH.id());
  }

  @Test
  void contextWindowValues() {
    assertEquals(1_048_576, GeminiModelId.GEMINI_3_FLASH_PREVIEW.contextWindow());
    assertEquals(1_048_576, GeminiModelId.GEMINI_3_1_PRO_PREVIEW.contextWindow());
    assertEquals(1_048_576, GeminiModelId.GEMINI_3_1_FLASH_LITE.contextWindow());
    assertEquals(1_048_576, GeminiModelId.GEMINI_3_5_FLASH.contextWindow());
    assertEquals(65_536, GeminiModelId.GEMINI_3_1_FLASH_LITE.maxOutputTokens());
    assertEquals(65_536, GeminiModelId.GEMINI_3_5_FLASH.maxOutputTokens());
  }

  @Test
  void fromIdReturnsMatchingEnum() {
    assertEquals(
        GeminiModelId.GEMINI_3_FLASH_PREVIEW, GeminiModelId.fromId("gemini-3-flash-preview"));
    assertEquals(
        GeminiModelId.GEMINI_3_1_PRO_PREVIEW, GeminiModelId.fromId("gemini-3.1-pro-preview"));
    assertEquals(
        GeminiModelId.GEMINI_3_1_FLASH_LITE, GeminiModelId.fromId("gemini-3.1-flash-lite"));
    assertEquals(GeminiModelId.GEMINI_3_5_FLASH, GeminiModelId.fromId("gemini-3.5-flash"));
  }

  @Test
  void fromIdReturnsNullForUnknown() {
    assertNull(GeminiModelId.fromId("unknown-model"));
  }

  @Test
  void fromIdReturnsNullForNull() {
    assertNull(GeminiModelId.fromId(null));
  }

  @Test
  void fromIdReturnsNullForBlank() {
    assertNull(GeminiModelId.fromId(""));
    assertNull(GeminiModelId.fromId("   "));
  }

  @Test
  void isSupportedReturnsTrueForKnownModels() {
    assertTrue(GeminiModelId.isSupported("gemini-3-flash-preview"));
    assertTrue(GeminiModelId.isSupported("gemini-3.1-pro-preview"));
    assertTrue(GeminiModelId.isSupported("gemini-3.1-flash-lite"));
    assertTrue(GeminiModelId.isSupported("gemini-3.5-flash"));
  }

  @Test
  void flashLitePreviewIdIsRetiredAndNotSupported() {
    // Per Google's May 2026 GA announcement, gemini-3.1-flash-lite-preview shuts down
    // 2026-05-25. Helios exposes the GA id only — the preview alias must NOT resolve.
    assertNull(GeminiModelId.fromId("gemini-3.1-flash-lite-preview"));
    assertFalse(GeminiModelId.isSupported("gemini-3.1-flash-lite-preview"));
  }

  @Test
  void isSupportedReturnsFalseForUnknownModels() {
    assertFalse(GeminiModelId.isSupported("unknown-model"));
    assertFalse(GeminiModelId.isSupported(null));
    assertFalse(GeminiModelId.isSupported(""));
  }
}
