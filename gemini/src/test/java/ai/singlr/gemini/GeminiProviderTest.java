/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.ModelConfig;
import org.junit.jupiter.api.Test;

class GeminiProviderTest {

  private final GeminiProvider provider = new GeminiProvider();

  @Test
  void name() {
    assertEquals("gemini", provider.name());
  }

  @Test
  void supportsSupportedModels() {
    assertTrue(provider.supports(GeminiModelId.GEMINI_3_FLASH_PREVIEW.id()));
    assertTrue(provider.supports(GeminiModelId.GEMINI_3_1_PRO_PREVIEW.id()));
    assertTrue(provider.supports(GeminiModelId.GEMINI_3_1_FLASH_LITE.id()));
    assertTrue(provider.supports(GeminiModelId.GEMINI_3_5_FLASH.id()));
  }

  @Test
  void doesNotSupportUnsupportedGeminiModels() {
    assertFalse(provider.supports("gemini-2.0-flash"));
    assertFalse(provider.supports("gemini-future-model"));
  }

  @Test
  void doesNotSupportNullModelId() {
    assertFalse(provider.supports(null));
  }

  @Test
  void doesNotSupportOtherProviderModels() {
    assertFalse(provider.supports("gpt-4"));
    assertFalse(provider.supports("claude-3-opus"));
    assertFalse(provider.supports("llama-3"));
  }

  @Test
  void createModelReturnsGeminiModel() {
    var config = ModelConfig.of("test-api-key");

    var model = provider.create(GeminiModelId.GEMINI_3_FLASH_PREVIEW.id(), config);

    assertNotNull(model);
    assertInstanceOf(GeminiModel.class, model);
    assertEquals(GeminiModelId.GEMINI_3_FLASH_PREVIEW.id(), model.id());
    assertEquals("gemini", model.provider());
  }

  @Test
  void createThrowsForUnsupportedModel() {
    var config = ModelConfig.of("test-api-key");

    var exception =
        assertThrows(IllegalArgumentException.class, () -> provider.create("gpt-4", config));

    assertEquals("Unsupported model: gpt-4", exception.getMessage());
  }
}
