/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.provider.StreamingModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AnthropicProviderTest {

  private final AnthropicProvider provider = new AnthropicProvider();

  @Test
  void aMissingConfigFailsBeforeTheModelIdIsResolved() {
    var thrown =
        assertThrows(IllegalArgumentException.class, () -> provider.create("acme-unknown", null));

    assertEquals("config is required", thrown.getMessage());
  }

  @Test
  void name() {
    assertEquals("anthropic", provider.name());
  }

  @Test
  void supportsSupportedModels() {
    assertTrue(provider.supports(AnthropicModelId.CLAUDE_OPUS_5.id()));
    assertTrue(provider.supports(AnthropicModelId.CLAUDE_MYTHOS_5.id()));
    assertTrue(provider.supports(AnthropicModelId.CLAUDE_HAIKU_5_5.id()));
  }

  @Test
  void supportsUnrecognisedClaudeIdsByPrefix() {
    assertTrue(provider.supports("claude-opus-6"));
    assertTrue(provider.supports("claude-future-model"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "claude-opus-4-8",
        "claude-sonnet-4-6",
        "claude-haiku-4-5-20251001",
        "claude-3-5-sonnet-20241022",
        "claude-2.1",
        "claude-v1",
        "claude-v1.3",
        "claude-instant-v1.0"
      })
  void aClaudeIdBeforeGeneration5IsRefused(String id) {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-api-key")
            .withBaseUrl("https://proxy.example/v1")
            .build();

    var thrown = assertThrows(IllegalArgumentException.class, () -> provider.create(id, config));

    assertEquals(
        "Claude model "
            + id
            + " is not supported: Helios supports Claude 5 and later (claude-fable-5-2,"
            + " claude-mythos-5-2, claude-fable-5-1, claude-mythos-5-1, claude-fable-5,"
            + " claude-mythos-5, claude-opus-5-5, claude-opus-5, claude-sonnet-5-5,"
            + " claude-sonnet-5, claude-haiku-5-5)",
        thrown.getMessage());
    assertFalse(provider.supports(id));
  }

  @Test
  void doesNotSupportNullModelId() {
    assertFalse(provider.supports(null));
  }

  @Test
  void doesNotSupportOtherProviderModels() {
    assertFalse(provider.supports("gpt-4"));
    assertFalse(provider.supports("gemini-3.8-flash"));
    assertFalse(provider.supports("llama-3"));
  }

  @Test
  void createModelReturnsAStreamingModelWithShortLivedCaching() {
    var config = ModelConfig.of("test-api-key");

    var model = provider.create(AnthropicModelId.CLAUDE_SONNET_5_5.id(), config);

    assertNotNull(model);
    assertEquals(AnthropicModelId.CLAUDE_SONNET_5_5.id(), model.id());
    assertEquals("anthropic", model.provider());
    assertEquals(
        SentCacheControl.SHORT_LIVED,
        SentCacheControl.onSystemPrompt(
            at -> provider.create(AnthropicModelId.CLAUDE_SONNET_5_5.id(), at)));
  }

  @Test
  void createModelAcceptsExplicitCachePolicy() {
    assertEquals(
        SentCacheControl.LONG_LIVED,
        SentCacheControl.onSystemPrompt(
            at ->
                provider.create(AnthropicModelId.CLAUDE_OPUS_5.id(), at, CachePolicy.longLived())));
  }

  @Test
  void createModelCanDisablePromptCaching() {
    assertNull(
        SentCacheControl.onSystemPrompt(
            at ->
                provider.create(AnthropicModelId.CLAUDE_OPUS_5.id(), at, CachePolicy.disabled())));
  }

  @Test
  void createUnknownClaudeModelAcceptsExplicitCachePolicy() {
    var config = ModelConfig.of("test-api-key");

    var model = provider.create("claude-future-model", config, CachePolicy.longLived());

    assertEquals("claude-future-model", model.id());
    assertEquals(
        SentCacheControl.LONG_LIVED,
        SentCacheControl.onSystemPrompt(
            at -> provider.create("claude-future-model", at, CachePolicy.longLived())));
  }

  @Test
  void createModelRejectsNullCachePolicy() {
    var config = ModelConfig.of("test-api-key");

    var exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> provider.create(AnthropicModelId.CLAUDE_OPUS_5.id(), config, (CachePolicy) null));

    assertEquals("cachePolicy is required", exception.getMessage());
  }

  @Test
  void createModelWithAlias() {
    var config = ModelConfig.of("test-api-key");

    var model = provider.create("claude-sonnet-5-5", config);

    assertNotNull(model);
    assertInstanceOf(StreamingModel.class, model);
    assertEquals(AnthropicModelId.CLAUDE_SONNET_5_5.id(), model.id());
  }

  @Test
  void createAcceptsUnrecognisedClaudeIdAgainstDefaultEndpoint() {
    var config = ModelConfig.of("test-api-key");

    var model = provider.create("claude-opus-6", config);

    assertNotNull(model);
    assertInstanceOf(StreamingModel.class, model);
    assertEquals("claude-opus-6", model.id());
  }

  @Test
  void createPassesUnknownClaudeIdVerbatim() {
    var config = ModelConfig.of("test-api-key");

    var model = provider.create("claude-some-unreleased-model", config);

    assertEquals("claude-some-unreleased-model", model.id());
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

  @Test
  void createAcceptsNonClaudeIdWhenBaseUrlSet() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-api-key")
            .withBaseUrl("https://proxy.example/v1")
            .build();

    var model = provider.create("some-proxy-model", config);

    assertEquals("some-proxy-model", model.id());
  }
}
