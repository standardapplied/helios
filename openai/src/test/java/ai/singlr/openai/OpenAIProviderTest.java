/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.ModelConfig;
import org.junit.jupiter.api.Test;

class OpenAIProviderTest {

  private final OpenAIProvider provider = new OpenAIProvider();

  @Test
  void providerName() {
    assertEquals("openai", provider.name());
  }

  @Test
  void supportsGpt4o() {
    assertTrue(provider.supports("gpt-4o"));
  }

  @Test
  void supportsGpt4oMini() {
    assertTrue(provider.supports("gpt-4o-mini"));
  }

  @Test
  void supportsGpt41() {
    assertTrue(provider.supports("gpt-4.1"));
  }

  @Test
  void supportsGpt41Mini() {
    assertTrue(provider.supports("gpt-4.1-mini"));
  }

  @Test
  void supportsGpt54() {
    assertTrue(provider.supports("gpt-5.4"));
  }

  @Test
  void supportsGpt56Sol() {
    assertTrue(provider.supports("gpt-5.6-sol"));
  }

  @Test
  void supportsO3() {
    assertTrue(provider.supports("o3"));
  }

  @Test
  void supportsO4Mini() {
    assertTrue(provider.supports("o4-mini"));
  }

  @Test
  void doesNotSupportUnknown() {
    assertFalse(provider.supports("davinci-003"));
  }

  @Test
  void doesNotSupportClaude() {
    assertFalse(provider.supports("claude-sonnet-4-6"));
  }

  @Test
  void doesNotSupportGemini() {
    assertFalse(provider.supports("gemini-3-flash-preview"));
  }

  @Test
  void createReturnsModel() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = provider.create("gpt-4o", config);
    assertNotNull(model);
    assertInstanceOf(OpenAIModel.class, model);
    assertEquals("gpt-4o", model.id());
  }

  @Test
  void createUnsupportedModelThrows() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    assertThrows(IllegalArgumentException.class, () -> provider.create("unknown-model", config));
  }
}
