/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.openai;

import static com.standardapplied.helios.openai.OpenAIFixture.createModel;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.HttpClientFactory;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.test.BoundedErrorBodyContract;
import com.standardapplied.helios.openai.api.InputItem;
import com.standardapplied.helios.openai.api.ResponsesRequest;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenAIModelTest extends BoundedErrorBodyContract {

  private static OpenAIStreams streams(ModelConfig config) {
    return new OpenAIStreams(config, HttpClientFactory.create(config));
  }

  @Test
  void constructorRequiresModelId() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withBaseUrl("https://proxy.example/v1/responses")
            .build();
    var error =
        assertThrows(
            IllegalArgumentException.class, () -> new OpenAIProvider().create(null, config));
    assertEquals("modelId is required", error.getMessage());
  }

  @Test
  void constructorRequiresConfig() {
    assertThrows(IllegalArgumentException.class, () -> createModel(OpenAIModelId.GPT_4O, null));
  }

  @Test
  void constructorRequiresApiKey() {
    var config = ModelConfig.newBuilder().build();
    assertThrows(IllegalArgumentException.class, () -> createModel(OpenAIModelId.GPT_4O, config));
  }

  @Test
  void constructorRequiresNonBlankApiKey() {
    var config = ModelConfig.newBuilder().withApiKey("   ").build();
    assertThrows(IllegalArgumentException.class, () -> createModel(OpenAIModelId.GPT_4O, config));
  }

  @Test
  void idReturnsModelId() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = createModel(OpenAIModelId.GPT_4O, config);
    assertEquals("gpt-4o", model.id());
  }

  @Test
  void providerReturnsOpenai() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = createModel(OpenAIModelId.GPT_4O, config);
    assertEquals("openai", model.provider());
  }

  @Test
  void contextWindowReturnsModelValue() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = createModel(OpenAIModelId.GPT_4O, config);
    assertEquals(128_000, model.contextWindow());
  }

  @Test
  void contextWindowConfigOverrideWins() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").withContextWindow(64_000).build();
    var model = createModel(OpenAIModelId.GPT_4O, config);
    assertEquals(64_000, model.contextWindow());
  }

  @Test
  void modelExposesMaxOutputTokensFromModelId() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = createModel(OpenAIModelId.GPT_5_5, config);
    assertEquals(OpenAIModelId.GPT_5_5.maxOutputTokens(), model.maxOutputTokens());
  }

  @Test
  void webSearchFailsFastAtConstruction() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").withWebSearch(true).build();

    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> createModel(OpenAIModelId.GPT_5_6, config));
    assertTrue(ex.getMessage().contains("webSearch"));
  }

  @Test
  void webFetchFailsFastAtConstruction() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").withWebFetch(true).build();

    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> createModel(OpenAIModelId.GPT_5_6, config));
    assertTrue(ex.getMessage().contains("webFetch"));
  }

  @Test
  void serializeRequestProducesValidJson() {
    var streams = streams(ModelConfig.newBuilder().withApiKey("test-key").build());
    var request =
        ResponsesRequest.newBuilder()
            .withModel("gpt-4o")
            .withInput(List.of(InputItem.userMessage("Hello")))
            .withStream(true)
            .build();

    var json = streams.serialize(request);

    assertNotNull(json);
    assertTrue(json.contains("\"model\":\"gpt-4o\""));
    assertTrue(json.contains("\"stream\":true"));
  }

  @Test
  void buildHttpRequestSetsCorrectUri() {
    var config = ModelConfig.newBuilder().withApiKey("sk-test-key").build();
    var streams = streams(config);

    var httpRequest = streams.httpRequest("{\"model\":\"gpt-4o\"}");

    assertEquals("POST", httpRequest.method());
    assertEquals(URI.create("https://api.openai.com/v1/responses"), httpRequest.uri());
    assertEquals("application/json", httpRequest.headers().firstValue("Content-Type").get());
  }

  @Test
  void buildHttpRequestUsesDefaultTimeout() {
    var config = ModelConfig.newBuilder().withApiKey("sk-test-key").build();
    var streams = streams(config);

    var httpRequest = streams.httpRequest("{\"model\":\"gpt-4o\"}");

    assertTrue(httpRequest.timeout().isPresent());
    assertEquals(Duration.ofSeconds(60), httpRequest.timeout().get());
  }

  @Test
  void buildHttpRequestUsesCustomTimeout() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("sk-test-key")
            .withResponseTimeout(Duration.ofSeconds(30))
            .build();
    var streams = streams(config);

    var httpRequest = streams.httpRequest("{\"model\":\"gpt-4o\"}");

    assertTrue(httpRequest.timeout().isPresent());
    assertEquals(Duration.ofSeconds(30), httpRequest.timeout().get());
  }

  @Test
  void buildHttpRequestSkipsTimeoutWhenNull() {
    // Parity with the Anthropic and Gemini streams — when ModelConfig.responseTimeout is null we
    // must not pass null to HttpRequest.Builder.timeout (which NPEs).
    var config =
        ModelConfig.newBuilder().withApiKey("sk-test-key").withResponseTimeout(null).build();
    var streams = streams(config);

    var httpRequest = streams.httpRequest("{\"model\":\"gpt-4o\"}");

    assertTrue(httpRequest.timeout().isEmpty());
  }

  @Test
  void buildHttpRequestSendsDefaultAuthorizationHeader() {
    var config = ModelConfig.newBuilder().withApiKey("sk-test-key").build();
    var streams = streams(config);
    var httpRequest = streams.httpRequest("{}");
    assertEquals(
        "Bearer sk-test-key", httpRequest.headers().firstValue("Authorization").orElseThrow());
  }

  @Test
  void buildHttpRequestUsesConfiguredBaseUrl() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("sk-test-key")
            .withBaseUrl("https://my-llm-proxy.example/v1/responses")
            .build();
    var streams = streams(config);
    var httpRequest = streams.httpRequest("{}");
    assertEquals(URI.create("https://my-llm-proxy.example/v1/responses"), httpRequest.uri());
  }

  @Test
  void buildHttpRequestAzureModeSkipsDefaultAuthorizationWhenApiKeyBlank() {
    var config =
        ModelConfig.newBuilder()
            .withBaseUrl(
                "https://my-resource.openai.azure.com/openai/deployments/my-dep/responses?api-version=2024-08-01-preview")
            .withHeader("api-key", "azure-secret")
            .build();
    var streams = streams(config);
    var httpRequest = streams.httpRequest("{}");
    assertEquals(
        URI.create(
            "https://my-resource.openai.azure.com/openai/deployments/my-dep/responses?api-version=2024-08-01-preview"),
        httpRequest.uri());
    assertEquals("azure-secret", httpRequest.headers().firstValue("api-key").orElseThrow());
    assertTrue(
        httpRequest.headers().firstValue("Authorization").isEmpty(),
        "default Authorization is omitted when apiKey is blank — Azure gets a clean api-key only request");
  }

  @Test
  void constructorAllowsBlankApiKeyWhenBaseUrlSet() {
    var config = ModelConfig.newBuilder().withBaseUrl("https://proxy.example/v1/responses").build();
    try (var model = createModel(OpenAIModelId.GPT_4O, config)) {
      assertEquals(OpenAIModelId.GPT_4O.id(), model.id());
    }
    var httpRequest = streams(config).httpRequest("{}");
    assertTrue(httpRequest.headers().firstValue("Authorization").isEmpty());
  }

  @Test
  void constructorStillRequiresApiKeyWhenBaseUrlIsNull() {
    var config = ModelConfig.newBuilder().build();
    assertThrows(IllegalArgumentException.class, () -> createModel(OpenAIModelId.GPT_4O, config));
  }

  @Test
  void buildHttpRequestExtraHeadersAreAppended() {
    var config =
        ModelConfig.newBuilder().withApiKey("sk-test-key").withHeader("x-trace-id", "abc").build();
    var streams = streams(config);
    var httpRequest = streams.httpRequest("{}");
    assertEquals("abc", httpRequest.headers().firstValue("x-trace-id").orElseThrow());
    assertEquals(
        "Bearer sk-test-key", httpRequest.headers().firstValue("Authorization").orElseThrow());
  }

  @Test
  void closeReleasesHttpClientResources() {
    var model = createModel();
    model.close();
  }

  @Test
  void closeIsIdempotent() {
    var model = createModel();
    model.close();
    model.close();
    model.close();
  }

  @Test
  void modelUsableInTryWithResources() {
    try (var model = createModel()) {
      assertEquals(OpenAIModelId.GPT_4O.id(), model.id());
    }
  }
}
