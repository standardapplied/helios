/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.ThinkingLevel;
import com.standardapplied.helios.core.model.ToolChoice;
import com.standardapplied.helios.core.test.ConversationFixture;
import com.standardapplied.helios.core.test.Golden;
import com.standardapplied.helios.core.test.ModelHarness;
import com.standardapplied.helios.core.tool.Tool;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Characterization: the exact request body sent for a fixed matrix of models, thinking levels,
 * output schemas, tools and cache policies, pinned in {@code golden/anthropic/requests.json}.
 */
class AnthropicRequestSnapshotTest {

  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(SerializationFeature.INDENT_OUTPUT)
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .build();

  private static final List<String> REPLY = List.of(Golden.read("anthropic/requests-reply.sse"));

  private static final Map<String, String> THINKING =
      Map.of(
          AnthropicModel.THINKING_BLOCKS_KEY,
          "[{\"text\":\"Two cities, two calls.\",\"signature\":\"sig-1\"}]");

  private final Map<String, Object> snapshots = new LinkedHashMap<>();

  @Test
  void everyRequestMatchesItsSnapshot() {
    for (var id : AnthropicModelId.values()) {
      for (var level : List.of(ThinkingLevel.NONE, ThinkingLevel.MEDIUM)) {
        snapshot(
            id.id() + " " + level + " tools",
            modelAt(id, level, CachePolicy.shortLived()),
            withTools());
      }
    }
    var sonnet = AnthropicModelId.CLAUDE_SONNET_4_6;
    var medium = ThinkingLevel.MEDIUM;
    snapshot("schema", modelAt(sonnet, medium, CachePolicy.shortLived()), withSchema(List.of()));
    snapshot(
        "schema and tools",
        modelAt(sonnet, medium, CachePolicy.shortLived()),
        withSchema(ConversationFixture.tools()));
    snapshot("cache disabled", modelAt(sonnet, medium, CachePolicy.disabled()), withTools());
    snapshot("cache long-lived", modelAt(sonnet, medium, CachePolicy.longLived()), withTools());
    snapshot(
        "web tools",
        uri ->
            new AnthropicModel(
                sonnet, config(uri, medium).withWebSearch(true).withWebFetch(true).build()),
        withTools());

    snapshot(
        "generation settings",
        uri ->
            new AnthropicModel(
                AnthropicModelId.CLAUDE_HAIKU_4_5,
                config(uri, ThinkingLevel.NONE)
                    .withTemperature(0.3)
                    .withTopP(0.9)
                    .withStopSequences(List.of("END"))
                    .withMaxOutputTokens(2048)
                    .withToolChoice(ToolChoice.required("weather"))
                    .build()),
        withTools());

    Golden.assertMatches("anthropic/requests.json", JSON.writeValueAsString(snapshots));
  }

  private void snapshot(String name, Function<URI, Model> modelAt, Consumer<Model> call) {
    var body = ModelHarness.exchange(REPLY, modelAt, call).getLast().body();
    snapshots.put(name, JSON.readValue(body, Map.class));
  }

  private static Consumer<Model> withTools() {
    return model -> model.chat(ConversationFixture.history(THINKING), ConversationFixture.tools());
  }

  private static Consumer<Model> withSchema(List<Tool> tools) {
    return model ->
        model.chat(ConversationFixture.history(THINKING), tools, ConversationFixture.schema());
  }

  private static Function<URI, Model> modelAt(
      AnthropicModelId id, ThinkingLevel level, CachePolicy cachePolicy) {
    return uri -> new AnthropicModel(id, config(uri, level).build(), cachePolicy);
  }

  private static ModelConfig.Builder config(URI uri, ThinkingLevel level) {
    return ModelConfig.newBuilder()
        .withApiKey("test-key")
        .withBaseUrl(uri + "/v1/messages")
        .withThinkingLevel(level);
  }
}
