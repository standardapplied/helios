/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import static com.standardapplied.helios.anthropic.AnthropicFixture.model;
import static com.standardapplied.helios.anthropic.AnthropicFixture.requestFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Reasoning;
import com.standardapplied.helios.core.model.Reasoning.Display;
import com.standardapplied.helios.core.model.Reasoning.Level;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@code temperature} and {@code top_p} on every catalogued Claude model: sent exactly as set, or
 * rejected when the model is created, never dropped from a request.
 */
class AnthropicSamplingTest {

  private static final Reasoning EFFORT = new Reasoning.Effort(Level.LOW, Display.SUMMARY);

  @ParameterizedTest
  @EnumSource(AnthropicModelId.class)
  void everyCataloguedModelRejectsEachSamplingParameterWithoutReasoning(AnthropicModelId model) {
    rejects(model, b -> b.withTemperature(0.5), "temperature whenever it is set");
    rejects(model, b -> b.withTopP(0.9), "topP whenever it is set");
  }

  @ParameterizedTest
  @EnumSource(
      value = AnthropicModelId.class,
      names = {"CLAUDE_SONNET_5_5", "CLAUDE_OPUS_5", "CLAUDE_SONNET_5", "CLAUDE_HAIKU_5_5"})
  void everyModelThatStopsReasoningRejectsEachSamplingParameterWithOff(AnthropicModelId model) {
    var off = new Reasoning.Off();
    rejects(
        model, b -> b.withReasoning(off).withTemperature(1.0), "temperature whenever it is set");
    rejects(model, b -> b.withReasoning(off).withTopP(1.0), "topP whenever it is set");
  }

  @ParameterizedTest
  @EnumSource(AnthropicModelId.class)
  void everyCataloguedModelRejectsEachSamplingParameterWithEffort(AnthropicModelId model) {
    rejects(
        model, b -> b.withReasoning(EFFORT).withTemperature(1.0), "temperature whenever it is set");
    rejects(model, b -> b.withReasoning(EFFORT).withTopP(1.0), "topP whenever it is set");
  }

  @Test
  void anUncataloguedModelIsSentWhateverIsSet() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withReasoning(EFFORT)
            .withTemperature(0.5)
            .withTopP(0.5)
            .build();

    var request = requestFor("claude-opus-6", config);

    assertEquals(0.5, request.temperature());
    assertEquals(0.5, request.topP());
  }

  private static void rejects(
      AnthropicModelId model, UnaryOperator<ModelConfig.Builder> settings, String rule) {
    var config = settings.apply(ModelConfig.newBuilder().withApiKey("test-key")).build();

    var rejection =
        assertThrows(IllegalArgumentException.class, () -> model(model, config)).getMessage();

    assertTrue(rejection.startsWith("Model " + model.id() + " does not accept " + rule), rejection);
  }
}
