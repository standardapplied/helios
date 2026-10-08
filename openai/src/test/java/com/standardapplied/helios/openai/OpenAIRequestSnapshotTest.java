/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.openai;

import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Reasoning;
import com.standardapplied.helios.core.model.ToolChoice;
import com.standardapplied.helios.core.test.ConversationFixture;
import com.standardapplied.helios.core.test.Golden;
import com.standardapplied.helios.core.test.ModelHarness;
import com.standardapplied.helios.core.tool.Tool;
import java.net.URI;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Characterization: the exact request body sent for a fixed matrix of models, reasoning levels,
 * output schemas, tools and generation settings, pinned in {@code golden/openai/requests.json}.
 */
class OpenAIRequestSnapshotTest {

  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(SerializationFeature.INDENT_OUTPUT)
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .build();

  private static final List<String> REPLY = List.of(Golden.read("openai/requests-reply.sse"));

  private static final Map<String, String> REASONING =
      Map.of(OpenAIResponseAssembler.REASONING_KEY, "Two cities, two calls.");

  private static final Reasoning MEDIUM =
      new Reasoning.Effort(Reasoning.Level.MEDIUM, Reasoning.Display.SUMMARY);

  private final Map<String, Object> snapshots = new LinkedHashMap<>();

  @Test
  void everyRequestMatchesItsSnapshot() {
    for (var id : OpenAIModelId.values()) {
      snapshot(
          id.id() + " absent tools", modelAt(id, config(builder -> builder, null)), withTools());
    }
    for (var id : EnumSet.range(OpenAIModelId.GPT_6_ASTRA, OpenAIModelId.GPT_5_4_NANO)) {
      snapshot(
          id.id() + " medium summary tools",
          modelAt(id, config(builder -> builder, MEDIUM)),
          withTools());
    }
    var model = OpenAIModelId.GPT_5_6;
    snapshot("schema", modelAt(model, config(builder -> builder, MEDIUM)), withSchema(List.of()));
    snapshot(
        "schema and tools",
        modelAt(model, config(builder -> builder, MEDIUM)),
        withSchema(ConversationFixture.tools()));
    snapshot(
        "generation settings",
        modelAt(
            OpenAIModelId.GPT_4_1,
            config(
                builder ->
                    builder
                        .withTemperature(0.3)
                        .withTopP(0.9)
                        .withStopSequences(List.of("END"))
                        .withMaxOutputTokens(2048)
                        .withPromptCacheKey("tenant-7")
                        .withToolChoice(ToolChoice.required("weather")),
                new Reasoning.Off())),
        withTools());

    Golden.assertMatches("openai/requests.json", JSON.writeValueAsString(snapshots));
  }

  private void snapshot(String name, Function<URI, Model> modelAt, Consumer<Model> call) {
    var body = ModelHarness.exchange(REPLY, modelAt, call).getLast().body();
    snapshots.put(name, JSON.readValue(body, Map.class));
  }

  private static Consumer<Model> withTools() {
    return model -> model.chat(ConversationFixture.history(REASONING), ConversationFixture.tools());
  }

  private static Consumer<Model> withSchema(List<Tool> tools) {
    return model ->
        model.chat(ConversationFixture.history(REASONING), tools, ConversationFixture.schema());
  }

  private static Function<URI, Model> modelAt(OpenAIModelId id, Function<URI, ModelConfig> config) {
    return uri -> new OpenAIProvider().create(id.id(), config.apply(uri));
  }

  private static Function<URI, ModelConfig> config(
      Function<ModelConfig.Builder, ModelConfig.Builder> settings, Reasoning reasoning) {
    return uri ->
        settings
            .apply(
                ModelConfig.newBuilder()
                    .withApiKey("test-key")
                    .withBaseUrl(uri + "/v1/responses")
                    .withReasoning(reasoning))
            .build();
  }
}
