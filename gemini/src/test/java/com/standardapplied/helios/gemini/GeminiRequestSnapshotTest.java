/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.ThinkingLevel;
import com.standardapplied.helios.core.model.ToolChoice;
import com.standardapplied.helios.core.test.ConversationFixture;
import com.standardapplied.helios.core.test.Golden;
import com.standardapplied.helios.core.test.ModelHarness;
import com.standardapplied.helios.core.tool.Tool;
import java.net.URI;
import java.util.ArrayList;
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
 * output schemas, tools, continuation and generation settings, pinned in {@code
 * golden/gemini/requests.json}.
 */
class GeminiRequestSnapshotTest {

  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(SerializationFeature.INDENT_OUTPUT)
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .build();

  private static final List<String> REPLY = List.of(Golden.read("gemini/requests-reply.sse"));

  private static final Map<String, String> THOUGHTS =
      Map.of(
          GeminiResponseAssembler.THOUGHT_SIGNATURES_KEY,
          "sig-1" + GeminiResponseAssembler.SIGNATURE_DELIMITER + "sig-2");

  private final Map<String, Object> snapshots = new LinkedHashMap<>();

  @Test
  void everyRequestMatchesItsSnapshot() {
    for (var id : GeminiModelId.values()) {
      for (var level : List.of(ThinkingLevel.NONE, ThinkingLevel.MEDIUM)) {
        snapshot(
            id.id() + " " + level + " tools", modelAt(id, settings(level)), withTools(THOUGHTS));
      }
    }
    var flash = GeminiModelId.GEMINI_3_5_FLASH;
    var low = settings(ThinkingLevel.LOW);
    snapshot("schema", modelAt(flash, low), withSchema(List.of()));
    snapshot("schema and tools", modelAt(flash, low), withSchema(ConversationFixture.tools()));
    snapshot("continuation", modelAt(flash, low), continuation());
    snapshot(
        "continuation disabled",
        modelAt(flash, settings(ThinkingLevel.LOW).withProviderContinuation(false)),
        continuation());
    snapshot(
        "web search",
        modelAt(flash, settings(ThinkingLevel.LOW).withWebSearch(true)),
        withTools(THOUGHTS));
    snapshot(
        "url context",
        modelAt(flash, settings(ThinkingLevel.LOW).withWebFetch(true)),
        model -> model.chat(List.of(Message.user("Summarise https://example.com"))));
    snapshot(
        "generation settings",
        modelAt(
            flash,
            settings(ThinkingLevel.HIGH)
                .withStopSequences(List.of("END"))
                .withSeed(7L)
                .withMaxOutputTokens(2048)
                .withToolChoice(ToolChoice.required("weather"))),
        withTools(THOUGHTS));

    Golden.assertMatches("gemini/requests.json", JSON.writeValueAsString(snapshots));
  }

  private void snapshot(String name, Function<URI, Model> modelAt, Consumer<Model> call) {
    var body = ModelHarness.exchange(REPLY, modelAt, call).getLast().body();
    snapshots.put(name, JSON.readValue(body, Map.class));
  }

  private static Consumer<Model> withTools(Map<String, String> assistantMetadata) {
    return model ->
        model.chat(ConversationFixture.history(assistantMetadata), ConversationFixture.tools());
  }

  private static Consumer<Model> withSchema(List<Tool> tools) {
    return model ->
        model.chat(ConversationFixture.history(THOUGHTS), tools, ConversationFixture.schema());
  }

  private static Consumer<Model> continuation() {
    var metadata = new LinkedHashMap<>(THOUGHTS);
    metadata.put(ContinuationPoint.INTERACTION_ID_KEY, "int_prev");
    return model -> {
      var history = new ArrayList<>(ConversationFixture.history(metadata));
      history.add(Message.user("And Berlin?"));
      model.chat(history, ConversationFixture.tools());
    };
  }

  private static ModelConfig.Builder settings(ThinkingLevel level) {
    return ModelConfig.newBuilder().withApiKey("test-key").withThinkingLevel(level);
  }

  private static Function<URI, Model> modelAt(GeminiModelId id, ModelConfig.Builder settings) {
    return uri ->
        new GeminiProvider().create(id.id(), settings.withBaseUrl(uri + "/v1beta").build());
  }
}
