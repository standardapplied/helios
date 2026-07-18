/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelConfigTest {

  @Test
  void ofFactoryMethod() {
    var config = ModelConfig.of("test-api-key");

    assertEquals("test-api-key", config.apiKey());
    assertEquals(ThinkingLevel.NONE, config.thinkingLevel());
    assertEquals(Duration.ofSeconds(10), config.connectTimeout());
    assertEquals(Duration.ofSeconds(60), config.responseTimeout());
  }

  @Test
  void builderWithAllFields() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("my-api-key")
            .withThinkingLevel(ThinkingLevel.HIGH)
            .withConnectTimeout(Duration.ofSeconds(30))
            .withResponseTimeout(Duration.ofMinutes(2))
            .build();

    assertEquals("my-api-key", config.apiKey());
    assertEquals(ThinkingLevel.HIGH, config.thinkingLevel());
    assertEquals(Duration.ofSeconds(30), config.connectTimeout());
    assertEquals(Duration.ofMinutes(2), config.responseTimeout());
  }

  @Test
  void builderWithDefaults() {
    var config = ModelConfig.newBuilder().withApiKey("key").build();

    assertEquals("key", config.apiKey());
    assertEquals(ThinkingLevel.NONE, config.thinkingLevel());
    assertEquals(Duration.ofSeconds(10), config.connectTimeout());
    assertEquals(Duration.ofSeconds(60), config.responseTimeout());
  }

  @Test
  void builderPartialOverride() {
    var config =
        ModelConfig.newBuilder().withApiKey("key").withThinkingLevel(ThinkingLevel.MEDIUM).build();

    assertEquals(ThinkingLevel.MEDIUM, config.thinkingLevel());
    assertEquals(Duration.ofSeconds(10), config.connectTimeout());
    assertEquals(Duration.ofSeconds(60), config.responseTimeout());
  }

  @Test
  void builderWithGenerationParams() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("key")
            .withTemperature(0.7)
            .withTopP(0.9)
            .withMaxOutputTokens(1024)
            .withStopSequences(List.of("END", "STOP"))
            .withSeed(42L)
            .build();

    assertEquals(0.7, config.temperature());
    assertEquals(0.9, config.topP());
    assertEquals(1024, config.maxOutputTokens());
    assertEquals(List.of("END", "STOP"), config.stopSequences());
    assertEquals(42L, config.seed());
  }

  @Test
  void builderWithToolChoice() {
    var config =
        ModelConfig.newBuilder().withApiKey("key").withToolChoice(ToolChoice.any()).build();

    assertEquals(ToolChoice.any(), config.toolChoice());
  }

  @Test
  void copyBuilder() {
    var original =
        ModelConfig.newBuilder()
            .withApiKey("key")
            .withThinkingLevel(ThinkingLevel.HIGH)
            .withTemperature(0.5)
            .withMaxOutputTokens(512)
            .build();

    var copy = ModelConfig.newBuilder(original).withTemperature(0.9).build();

    assertEquals("key", copy.apiKey());
    assertEquals(ThinkingLevel.HIGH, copy.thinkingLevel());
    assertEquals(0.9, copy.temperature());
    assertEquals(512, copy.maxOutputTokens());
  }

  @Test
  void generationParamsDefaultToNull() {
    var config = ModelConfig.of("key");

    assertNull(config.temperature());
    assertNull(config.topP());
    assertNull(config.maxOutputTokens());
    assertNull(config.contextWindow());
    assertNull(config.stopSequences());
    assertNull(config.seed());
    assertNull(config.toolChoice());
  }

  @Test
  void builderWithContextWindow() {
    var config = ModelConfig.newBuilder().withApiKey("key").withContextWindow(500_000).build();

    assertEquals(500_000, config.contextWindow());
  }

  @Test
  void copyBuilderPreservesContextWindow() {
    var original = ModelConfig.newBuilder().withApiKey("key").withContextWindow(500_000).build();
    var copy = ModelConfig.newBuilder(original).withTemperature(0.5).build();

    assertEquals(500_000, copy.contextWindow());
  }

  @Test
  void webSearchAndWebFetchDefaultFalse() {
    var config = ModelConfig.of("key");

    assertFalse(config.webSearch());
    assertFalse(config.webFetch());
  }

  @Test
  void builderWithWebSearchAndWebFetch() {
    var config =
        ModelConfig.newBuilder().withApiKey("key").withWebSearch(true).withWebFetch(true).build();

    assertTrue(config.webSearch());
    assertTrue(config.webFetch());
  }

  @Test
  void copyBuilderPreservesWebSearchAndWebFetch() {
    var original =
        ModelConfig.newBuilder().withApiKey("key").withWebSearch(true).withWebFetch(true).build();
    var copy = ModelConfig.newBuilder(original).withTemperature(0.5).build();

    assertTrue(copy.webSearch());
    assertTrue(copy.webFetch());
  }

  @Test
  @SuppressWarnings("deprecation")
  void deprecatedGoogleSearchAliasesWebSearch() {
    var config = ModelConfig.newBuilder().withApiKey("key").withGoogleSearch(true).build();

    assertTrue(config.webSearch());
    assertTrue(config.googleSearch());
  }

  @Test
  @SuppressWarnings("deprecation")
  void deprecatedUrlContextAliasesWebFetch() {
    var config = ModelConfig.newBuilder().withApiKey("key").withUrlContext(true).build();

    assertTrue(config.webFetch());
    assertTrue(config.urlContext());
  }

  @Test
  void toStringShowsWebToggles() {
    var config = ModelConfig.newBuilder().withApiKey("key").withWebSearch(true).build();

    assertTrue(config.toString().contains("webSearch=true"));
    assertTrue(config.toString().contains("webFetch=false"));
  }

  @Test
  void streamIdleTimeoutDefaultsTo120Seconds() {
    var config = ModelConfig.of("key");

    assertEquals(Duration.ofSeconds(120), config.streamIdleTimeout());
  }

  @Test
  void builderWithStreamIdleTimeout() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("key")
            .withStreamIdleTimeout(Duration.ofSeconds(15))
            .build();

    assertEquals(Duration.ofSeconds(15), config.streamIdleTimeout());
  }

  @Test
  void copyBuilderPreservesStreamIdleTimeout() {
    var original =
        ModelConfig.newBuilder()
            .withApiKey("key")
            .withStreamIdleTimeout(Duration.ofSeconds(45))
            .build();
    var copy = ModelConfig.newBuilder(original).withTemperature(0.5).build();

    assertEquals(Duration.ofSeconds(45), copy.streamIdleTimeout());
  }

  @Test
  void baseUrlDefaultsToNull() {
    var config = ModelConfig.of("k");
    assertNull(config.baseUrl());
  }

  @Test
  void withBaseUrlStores() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("k")
            .withBaseUrl("https://my-proxy.example.com/v1")
            .build();
    assertEquals("https://my-proxy.example.com/v1", config.baseUrl());
  }

  @Test
  void effectiveBaseUrlFallsBackToDefaultWhenNull() {
    assertEquals(
        "https://api.openai.com/v1/responses",
        ModelConfig.of("k").effectiveBaseUrl("https://api.openai.com/v1/responses"));
  }

  @Test
  void effectiveBaseUrlPrefersConfiguredValue() {
    var config =
        ModelConfig.newBuilder().withApiKey("k").withBaseUrl("https://x.example/v").build();
    assertEquals("https://x.example/v", config.effectiveBaseUrl("https://default"));
  }

  @Test
  void effectiveBaseUrlIgnoresBlank() {
    var config = ModelConfig.newBuilder().withApiKey("k").withBaseUrl("   ").build();
    assertEquals("https://default", config.effectiveBaseUrl("https://default"));
  }

  @Test
  void headersDefaultToEmpty() {
    var config = ModelConfig.of("k");
    assertTrue(config.headers().isEmpty());
  }

  @Test
  void withHeadersStoresImmutableCopy() {
    var input = new java.util.LinkedHashMap<String, String>();
    input.put("api-key", "k");
    var config = ModelConfig.newBuilder().withApiKey("k").withHeaders(input).build();
    input.put("api-key", "mutated");
    assertEquals("k", config.headers().get("api-key"));
    assertThrows(UnsupportedOperationException.class, () -> config.headers().put("x", "y"));
  }

  @Test
  void withHeadersNullClearsExistingEntries() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("k")
            .withHeader("api-key", "v")
            .withHeaders(null)
            .build();
    assertTrue(config.headers().isEmpty());
  }

  @Test
  void withHeaderAddsAndReplacesByExactName() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("k")
            .withHeader("api-key", "first")
            .withHeader("api-key", "second")
            .withHeader("x-custom", "v")
            .build();
    assertEquals("second", config.headers().get("api-key"));
    assertEquals("v", config.headers().get("x-custom"));
  }

  @Test
  void withHeaderRejectsBlankName() {
    var builder = ModelConfig.newBuilder().withApiKey("k");
    assertThrows(IllegalArgumentException.class, () -> builder.withHeader("", "v"));
    assertThrows(IllegalArgumentException.class, () -> builder.withHeader(null, "v"));
  }

  @Test
  void effectiveHeadersPreservesDefaultsWhenUserMapEmpty() {
    var config = ModelConfig.of("k");
    var defaults = Map.of("Authorization", "Bearer k", "Content-Type", "application/json");
    var merged = config.effectiveHeaders(defaults);
    assertEquals("Bearer k", merged.get("Authorization"));
    assertEquals("application/json", merged.get("Content-Type"));
  }

  @Test
  void effectiveHeadersUserHeaderWithDifferentNameLeavesDefaultsIntact() {
    var config = ModelConfig.newBuilder().withApiKey("k").withHeader("api-key", "azure-k").build();
    var defaults = Map.of("Authorization", "Bearer k");
    var merged = config.effectiveHeaders(defaults);
    assertEquals("azure-k", merged.get("api-key"));
    assertEquals(
        "Bearer k",
        merged.get("Authorization"),
        "merge is additive when names differ — to drop a default, the provider must omit it (e.g. blank apiKey + baseUrl)");
  }

  @Test
  void effectiveHeadersUserOverrideMatchesIgnoringCase() {
    var config =
        ModelConfig.newBuilder().withApiKey("k").withHeader("CONTENT-TYPE", "text/xml").build();
    var defaults = Map.of("Content-Type", "application/json");
    var merged = config.effectiveHeaders(defaults);
    assertEquals(1, merged.size());
    assertEquals("text/xml", merged.get("CONTENT-TYPE"));
  }

  @Test
  void effectiveHeadersAppendsUserOnlyEntriesAfterDefaults() {
    var config = ModelConfig.newBuilder().withApiKey("k").withHeader("x-trace-id", "abc").build();
    var defaults = Map.of("Authorization", "Bearer k");
    var merged = config.effectiveHeaders(defaults);
    assertEquals(2, merged.size());
    var iter = merged.entrySet().iterator();
    assertEquals("Authorization", iter.next().getKey());
    assertEquals("x-trace-id", iter.next().getKey());
  }

  @Test
  void effectiveHeadersAcceptsNullDefaults() {
    var config = ModelConfig.newBuilder().withApiKey("k").withHeader("a", "1").build();
    var merged = config.effectiveHeaders(null);
    assertEquals(Map.of("a", "1"), merged);
  }

  @Test
  void toStringRedactsApiKey() {
    var config = ModelConfig.of("sk-supersecret-abc123-do-not-log");
    var rendered = config.toString();
    assertFalse(
        rendered.contains("sk-supersecret-abc123-do-not-log"),
        "ModelConfig.toString must not leak the API key into logs/exceptions/crash dumps");
    assertTrue(
        rendered.contains("apiKey="),
        "toString should still mention the apiKey field for diagnostic context");
  }

  @Test
  void toStringWithNullApiKeyDoesNotCrash() {
    var config = ModelConfig.newBuilder().build();
    var rendered = config.toString();
    assertTrue(rendered.contains("apiKey=null"));
  }

  @Test
  void toStringRedactsHeaderValues() {
    // Users can stick secrets into arbitrary header values (Azure api-key, x-api-key, custom
    // bearer tokens). The toString must not echo them back into logs.
    var config =
        ModelConfig.newBuilder()
            .withApiKey("k")
            .withHeader("api-key", "azure-secret-xyz")
            .withHeader("Authorization", "Bearer leak-me")
            .build();
    var rendered = config.toString();
    assertFalse(rendered.contains("azure-secret-xyz"));
    assertFalse(rendered.contains("Bearer leak-me"));
    assertTrue(rendered.contains("api-key"), "header names are safe to show");
    assertTrue(rendered.contains("Authorization"));
  }

  @Test
  void copyBuilderPreservesBaseUrlAndHeaders() {
    var original =
        ModelConfig.newBuilder()
            .withApiKey("k")
            .withBaseUrl("https://x.example")
            .withHeader("api-key", "v")
            .build();
    var copy = ModelConfig.newBuilder(original).withTemperature(0.5).build();
    assertEquals("https://x.example", copy.baseUrl());
    assertEquals("v", copy.headers().get("api-key"));
  }
}
