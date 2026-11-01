/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class GeminiModelIdTest {

  @Test
  void theCatalogueIsExactlyTheFourSupportedModels() {
    assertEquals(
        EnumSet.of(
            GeminiModelId.GEMINI_3_6_FLASH,
            GeminiModelId.GEMINI_3_7_FLASH,
            GeminiModelId.GEMINI_3_8_FLASH,
            GeminiModelId.GEMINI_4_ARGON),
        EnumSet.allOf(GeminiModelId.class));
  }

  @Test
  void enumHasCorrectId() {
    assertEquals("gemini-3.6-flash", GeminiModelId.GEMINI_3_6_FLASH.id());
    assertEquals("gemini-3.7-flash", GeminiModelId.GEMINI_3_7_FLASH.id());
    assertEquals("gemini-3.8-flash", GeminiModelId.GEMINI_3_8_FLASH.id());
    assertEquals("gemini-4-argon", GeminiModelId.GEMINI_4_ARGON.id());
  }

  @ParameterizedTest
  @EnumSource(GeminiModelId.class)
  void everyModelHasTheDocumentedWindowAndOutputCeiling(GeminiModelId model) {
    assertEquals(1_048_576, model.contextWindow());
    assertEquals(65_536, model.maxOutputTokens());
  }

  @ParameterizedTest
  @EnumSource(GeminiModelId.class)
  void fromIdAndIsSupportedResolveEveryModel(GeminiModelId model) {
    assertEquals(model, GeminiModelId.fromId(model.id()));
    assertTrue(GeminiModelId.isSupported(model.id()));
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
  void isSupportedReturnsFalseForUnknownModels() {
    assertFalse(GeminiModelId.isSupported("unknown-model"));
    assertFalse(GeminiModelId.isSupported(null));
    assertFalse(GeminiModelId.isSupported(""));
  }
}
