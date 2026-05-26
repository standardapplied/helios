/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.ModelConfig;
import org.junit.jupiter.api.Test;

class AnthropicProviderTest {

  private final AnthropicProvider provider = new AnthropicProvider();

  @Test
  void name() {
    assertEquals("anthropic", provider.name());
  }

  @Test
  void supportsSupportedModels() {
    assertTrue(provider.supports(AnthropicModelId.CLAUDE_OPUS_4_6.id()));
    assertTrue(provider.supports(AnthropicModelId.CLAUDE_SONNET_4_6.id()));
  }

  @Test
  void supportsAliases() {
    assertTrue(provider.supports("claude-opus-4-6"));
    assertTrue(provider.supports("claude-sonnet-4-6"));
  }

  @Test
  void doesNotSupportUnsupportedModels() {
    assertFalse(provider.supports("claude-2.1"));
    assertFalse(provider.supports("claude-future-model"));
  }

  @Test
  void doesNotSupportNullModelId() {
    assertFalse(provider.supports(null));
  }

  @Test
  void doesNotSupportOtherProviderModels() {
    assertFalse(provider.supports("gpt-4"));
    assertFalse(provider.supports("gemini-3-flash-preview"));
    assertFalse(provider.supports("llama-3"));
  }

  @Test
  void createModelReturnsAnthropicModel() {
    var config = ModelConfig.of("test-api-key");

    var model = provider.create(AnthropicModelId.CLAUDE_SONNET_4_6.id(), config);

    assertNotNull(model);
    assertInstanceOf(AnthropicModel.class, model);
    assertEquals(AnthropicModelId.CLAUDE_SONNET_4_6.id(), model.id());
    assertEquals("anthropic", model.provider());
  }

  @Test
  void createModelWithAlias() {
    var config = ModelConfig.of("test-api-key");

    var model = provider.create("claude-sonnet-4-6", config);

    assertNotNull(model);
    assertInstanceOf(AnthropicModel.class, model);
    assertEquals(AnthropicModelId.CLAUDE_SONNET_4_6.id(), model.id());
  }

  @Test
  void createThrowsForUnsupportedModel() {
    var config = ModelConfig.of("test-api-key");

    var exception =
        assertThrows(IllegalArgumentException.class, () -> provider.create("gpt-4", config));

    assertTrue(
        exception.getMessage().startsWith("Unsupported model: gpt-4"),
        () -> "expected 'Unsupported model' prefix, got: " + exception.getMessage());
  }
}
