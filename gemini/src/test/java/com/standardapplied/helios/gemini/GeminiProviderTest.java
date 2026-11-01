/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.ToolChoice;
import com.standardapplied.helios.core.provider.StreamingModel;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.gemini.api.GeminiJson;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class GeminiProviderTest {

  private final GeminiProvider provider = new GeminiProvider();

  @Test
  void aMissingConfigFailsBeforeTheModelIdIsResolved() {
    var thrown =
        assertThrows(IllegalArgumentException.class, () -> provider.create("acme-unknown", null));

    assertEquals("config is required", thrown.getMessage());
  }

  @Test
  void name() {
    assertEquals("gemini", provider.name());
  }

  @Test
  void supportsSupportedModels() {
    assertTrue(provider.supports("gemini-3.6-flash"));
    assertTrue(provider.supports("gemini-3.7-flash"));
    assertTrue(provider.supports("gemini-3.8-flash"));
    assertTrue(provider.supports("gemini-4-argon"));
  }

  @Test
  void doesNotSupportUnsupportedGeminiModels() {
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
  void createModelReturnsStreamingModel() {
    var config = ModelConfig.of("test-api-key");

    var model = provider.create(GeminiModelId.GEMINI_3_8_FLASH.id(), config);

    assertNotNull(model);
    assertInstanceOf(StreamingModel.class, model);
    assertEquals(GeminiModelId.GEMINI_3_8_FLASH.id(), model.id());
    assertEquals("gemini", model.provider());
  }

  @Test
  void createThrowsForUnsupportedModel() {
    var config = ModelConfig.of("test-api-key");

    var exception =
        assertThrows(IllegalArgumentException.class, () -> provider.create("gpt-4", config));

    assertEquals(
        "Unsupported model: gpt-4. Supported models: gemini-3.6-flash, gemini-3.7-flash,"
            + " gemini-3.8-flash, gemini-4-argon",
        exception.getMessage());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "3-flash-preview",
        "3.1-pro-preview",
        "3.1-flash-lite",
        "3.5-flash",
        "3.5-flash-lite"
      })
  void aRemovedModelIsRejectedNamingItAndTheSupportedModels(String version) {
    var removed = "gemini-" + version;
    var config = ModelConfig.of("test-api-key");

    var exception =
        assertThrows(IllegalArgumentException.class, () -> provider.create(removed, config));

    assertFalse(provider.supports(removed));
    assertEquals(
        "Unsupported model: "
            + removed
            + ". Supported models: gemini-3.6-flash, gemini-3.7-flash, gemini-3.8-flash,"
            + " gemini-4-argon",
        exception.getMessage());
  }

  @Test
  void gemini4ArgonIsCreatedWithItsWindowAndOutputCeiling() {
    var config = ModelConfig.of("test-api-key");

    try (var model = provider.create("gemini-4-argon", config)) {
      assertEquals("gemini-4-argon", model.id());
      assertEquals(1_048_576, model.contextWindow());
      assertEquals(65_536, model.maxOutputTokens());
    }
  }

  @Test
  void aGemini4ArgonRequestCarriesItsIdAndOutputCeiling() {
    var body = requestBody(GeminiModelId.GEMINI_4_ARGON, ModelConfig.of("test-api-key"), null);

    assertEquals("gemini-4-argon", body.get("model"));
    assertEquals(65_536, generationConfig(body).get("max_output_tokens"));
  }

  @ParameterizedTest
  @EnumSource(GeminiModelId.class)
  void requiredForcesACallToTheNamedToolOnEveryModel(GeminiModelId model) {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-api-key")
            .withToolChoice(ToolChoice.required("weather"))
            .build();
    var weather =
        Tool.newBuilder()
            .withName("weather")
            .withDescription("The weather")
            .withExecutor((arguments, context) -> ToolResult.success("sunny"))
            .build();

    var body = requestBody(model, config, List.of(weather));

    assertEquals(
        Map.of("allowed_tools", Map.of("mode", "any", "tools", List.of("weather"))),
        generationConfig(body).get("tool_choice"));
  }

  private static Map<?, ?> requestBody(GeminiModelId model, ModelConfig config, List<Tool> tools) {
    var request =
        new GeminiRequestBuilder(model, config)
            .build(List.of(Message.user("Hi")), tools, null)
            .request();
    return JsonMapper.builder()
        .build()
        .readValue(GeminiJson.LENIENT.writeValueAsString(request), Map.class);
  }

  private static Map<?, ?> generationConfig(Map<?, ?> body) {
    return (Map<?, ?>) body.get("generation_config");
  }
}
