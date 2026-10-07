/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.anthropic;

import static com.standardapplied.helios.anthropic.AnthropicFixture.model;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.ToolChoice;
import com.standardapplied.helios.core.test.BoundedErrorBodyContract;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnthropicModelTest extends BoundedErrorBodyContract {

  private static HttpRequest httpRequest(ModelConfig config) {
    try (var client = HttpClient.newHttpClient()) {
      return new AnthropicStreams(config, client).httpRequest("{}");
    }
  }

  @Test
  void constructorRequiresModelId() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withBaseUrl("https://proxy.example/v1/messages")
            .build();
    var ex = assertThrows(IllegalArgumentException.class, () -> model(" ", config));
    assertEquals("modelId is required", ex.getMessage());
  }

  @Test
  void constructorRequiresConfig() {
    assertThrows(
        IllegalArgumentException.class, () -> model(AnthropicModelId.CLAUDE_SONNET_4_6, null));
  }

  @Test
  void constructorRequiresApiKey() {
    var config = ModelConfig.newBuilder().build();
    assertThrows(
        IllegalArgumentException.class, () -> model(AnthropicModelId.CLAUDE_SONNET_4_6, config));
  }

  @Test
  void constructorRequiresNonBlankApiKey() {
    var config = ModelConfig.newBuilder().withApiKey("   ").build();
    assertThrows(
        IllegalArgumentException.class, () -> model(AnthropicModelId.CLAUDE_SONNET_4_6, config));
  }

  @Test
  void constructorRejectsNullCachePolicy() {
    var config = ModelConfig.newBuilder().withApiKey("sk").build();
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> model(AnthropicModelId.CLAUDE_OPUS_4_7, config, null));
    assertEquals("cachePolicy is required", ex.getMessage());
  }

  @Test
  void fable51RejectsForcedToolChoiceAtConstruction() {
    for (var choice : List.of(ToolChoice.any(), ToolChoice.required("my_tool"))) {
      var config = ModelConfig.newBuilder().withApiKey("test-key").withToolChoice(choice).build();
      var ex =
          assertThrows(
              IllegalArgumentException.class,
              () -> model(AnthropicModelId.CLAUDE_FABLE_5_1, config));
      assertTrue(ex.getMessage().contains("claude-fable-5-1"), ex.getMessage());
      assertTrue(ex.getMessage().contains("forced tool use"), ex.getMessage());
    }
    var wireConfig =
        ModelConfig.newBuilder().withApiKey("test-key").withToolChoice(ToolChoice.any()).build();
    assertThrows(IllegalArgumentException.class, () -> model("claude-mythos-5-1", wireConfig));
  }

  @Test
  void the55ModelsRejectForcedToolChoiceAtConstruction() {
    for (var modelId :
        List.of(AnthropicModelId.CLAUDE_OPUS_5_5, AnthropicModelId.CLAUDE_SONNET_5_5)) {
      for (var forced : List.of(ToolChoice.any(), ToolChoice.required("search_profiles"))) {
        var config = ModelConfig.newBuilder().withApiKey("test-key").withToolChoice(forced).build();

        var ex = assertThrows(IllegalArgumentException.class, () -> model(modelId, config));
        assertTrue(ex.getMessage().contains(modelId.id()), ex.getMessage());
        assertTrue(ex.getMessage().contains("ToolChoice.auto()"), ex.getMessage());
      }
    }
  }

  @Test
  void idReturnsModelId() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = model(AnthropicModelId.CLAUDE_SONNET_4_6, config);
    assertEquals(AnthropicModelId.CLAUDE_SONNET_4_6.id(), model.id());
  }

  @Test
  void providerReturnsAnthropic() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = model(AnthropicModelId.CLAUDE_SONNET_4_6, config);
    assertEquals("anthropic", model.provider());
  }

  @Test
  void contextWindowReturnsModelValue() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = model(AnthropicModelId.CLAUDE_OPUS_4_6, config);
    assertEquals(1_000_000, model.contextWindow());
  }

  @Test
  void contextWindowConfigOverrideWinsOverKnownModel() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").withContextWindow(250_000).build();
    var model = model(AnthropicModelId.CLAUDE_OPUS_4_6, config);
    assertEquals(250_000, model.contextWindow());
  }

  @Test
  void unknownClaudeModelContextWindowFromConfig() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").withContextWindow(750_000).build();
    var model = model("claude-some-future-model", config);
    assertEquals(750_000, model.contextWindow());
  }

  @Test
  void unknownClaudeModelContextWindowDefaultsToZeroWhenUnset() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = model("claude-some-future-model", config);
    assertEquals(0, model.contextWindow());
  }

  @Test
  void modelExposesMaxOutputTokensFromModelId() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = model(AnthropicModelId.CLAUDE_OPUS_4_7, config);
    assertEquals(AnthropicModelId.CLAUDE_OPUS_4_7.maxOutputTokens(), model.maxOutputTokens());
  }

  @Test
  void closeReleasesHttpClientResources() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = model(AnthropicModelId.CLAUDE_OPUS_4_6, config);
    model.close();
  }

  @Test
  void closeIsIdempotent() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = model(AnthropicModelId.CLAUDE_OPUS_4_6, config);
    model.close();
    model.close();
    model.close();
  }

  @Test
  void modelUsableInTryWithResources() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    try (var model = model(AnthropicModelId.CLAUDE_OPUS_4_6, config)) {
      assertEquals(AnthropicModelId.CLAUDE_OPUS_4_6.id(), model.id());
    }
  }

  @Test
  void buildHttpRequestUsesDefaultsWhenBaseUrlAndHeadersUnset() {
    var config = ModelConfig.newBuilder().withApiKey("sk-ant-test").build();
    var httpRequest = httpRequest(config);
    assertEquals(java.net.URI.create("https://api.anthropic.com/v1/messages"), httpRequest.uri());
    assertEquals("sk-ant-test", httpRequest.headers().firstValue("x-api-key").orElseThrow());
  }

  @Test
  void buildHttpRequestUsesConfiguredBaseUrl() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("sk-ant-test")
            .withBaseUrl("https://bedrock-anthropic.example/v1/messages")
            .build();
    var httpRequest = httpRequest(config);
    assertEquals(
        java.net.URI.create("https://bedrock-anthropic.example/v1/messages"), httpRequest.uri());
  }

  @Test
  void buildHttpRequestUserHeaderReplacesBuiltinByCaseInsensitiveName() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("default-key")
            .withHeader("X-API-KEY", "override-key")
            .build();
    var httpRequest = httpRequest(config);
    assertEquals("override-key", httpRequest.headers().firstValue("x-api-key").orElseThrow());
    assertEquals(
        1,
        httpRequest.headers().allValues("x-api-key").size(),
        "name match must replace the default rather than append a second header line");
  }

  @Test
  void buildHttpRequestExtraHeaderIsAppended() {
    var config =
        ModelConfig.newBuilder().withApiKey("sk-ant-test").withHeader("x-trace", "t1").build();
    var httpRequest = httpRequest(config);
    assertEquals("t1", httpRequest.headers().firstValue("x-trace").orElseThrow());
    assertEquals("sk-ant-test", httpRequest.headers().firstValue("x-api-key").orElseThrow());
  }
}
