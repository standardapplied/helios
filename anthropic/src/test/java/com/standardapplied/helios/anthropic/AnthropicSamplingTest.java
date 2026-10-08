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
import java.util.EnumSet;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code temperature} and {@code top_p} on every catalogued Claude model: sent exactly as set, or
 * rejected when the model is created, never dropped from a request.
 */
class AnthropicSamplingTest {

  private static final Reasoning EFFORT = new Reasoning.Effort(Level.LOW, Display.SUMMARY);

  @ParameterizedTest
  @EnumSource(
      value = AnthropicModelId.class,
      mode = EnumSource.Mode.EXCLUDE,
      names = {"CLAUDE_OPUS_4_6", "CLAUDE_SONNET_4_6", "CLAUDE_HAIKU_4_5"})
  void aModelFromClaude47OnRejectsEachSamplingParameterWithoutReasoning(AnthropicModelId model) {
    rejects(model, b -> b.withTemperature(0.5), "temperature whenever it is set");
    rejects(model, b -> b.withTopP(0.9), "topP whenever it is set");
  }

  @ParameterizedTest
  @EnumSource(
      value = AnthropicModelId.class,
      names = {
        "CLAUDE_SONNET_5_5",
        "CLAUDE_OPUS_5",
        "CLAUDE_SONNET_5",
        "CLAUDE_OPUS_4_8",
        "CLAUDE_OPUS_4_7"
      })
  void aModelFromClaude47OnRejectsEachSamplingParameterWithOff(AnthropicModelId model) {
    var off = new Reasoning.Off();
    rejects(
        model, b -> b.withReasoning(off).withTemperature(1.0), "temperature whenever it is set");
    rejects(model, b -> b.withReasoning(off).withTopP(1.0), "topP whenever it is set");
  }

  @ParameterizedTest
  @EnumSource(
      value = AnthropicModelId.class,
      mode = EnumSource.Mode.EXCLUDE,
      names = {"CLAUDE_OPUS_4_6", "CLAUDE_SONNET_4_6", "CLAUDE_HAIKU_4_5"})
  void aModelFromClaude47OnRejectsEachSamplingParameterWithEffort(AnthropicModelId model) {
    rejects(
        model, b -> b.withReasoning(EFFORT).withTemperature(1.0), "temperature whenever it is set");
    rejects(model, b -> b.withReasoning(EFFORT).withTopP(1.0), "topP whenever it is set");
  }

  @ParameterizedTest
  @EnumSource(
      value = AnthropicModelId.class,
      names = {"CLAUDE_OPUS_4_6", "CLAUDE_SONNET_4_6", "CLAUDE_HAIKU_4_5"})
  void claude46AndHaikuSendEitherParameterAloneWithoutEffort(AnthropicModelId model) {
    sends(model, b -> b.withTemperature(0.5), 0.5, null);
    sends(model, b -> b.withTopP(0.9), null, 0.9);
    sends(model, b -> b.withReasoning(new Reasoning.Off()).withTemperature(0.2), 0.2, null);
    rejects(model, b -> b.withTemperature(0.5).withTopP(0.9), "temperature and topP together");
  }

  @ParameterizedTest
  @ValueSource(doubles = {0.95, 1.0})
  void sonnet46SendsATopPFromPoint95To1WithEffort(double topP) {
    sends(
        AnthropicModelId.CLAUDE_SONNET_4_6,
        b -> b.withReasoning(EFFORT).withTopP(topP),
        null,
        topP);
  }

  @ParameterizedTest
  @ValueSource(doubles = {0.94, 1.01})
  void sonnet46RejectsATopPOutsidePoint95To1WithEffort(double topP) {
    rejects(
        AnthropicModelId.CLAUDE_SONNET_4_6,
        b -> b.withReasoning(EFFORT).withTopP(topP),
        "topP outside 0.95-1 with Reasoning.Effort");
  }

  @Test
  void claude46RejectsTemperatureWithEffort() {
    for (var model :
        EnumSet.of(AnthropicModelId.CLAUDE_OPUS_4_6, AnthropicModelId.CLAUDE_SONNET_4_6)) {
      rejects(
          model,
          b -> b.withReasoning(EFFORT).withTemperature(1.0),
          "temperature with Reasoning.Effort");
    }
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

  private static void sends(
      AnthropicModelId model,
      UnaryOperator<ModelConfig.Builder> settings,
      Double temperature,
      Double topP) {
    var config = settings.apply(ModelConfig.newBuilder().withApiKey("test-key")).build();
    model(model, config).close();

    var request = requestFor(model.id(), config);

    assertEquals(temperature, request.temperature(), model::id);
    assertEquals(topP, request.topP(), model::id);
  }

  private static void rejects(
      AnthropicModelId model, UnaryOperator<ModelConfig.Builder> settings, String rule) {
    var config = settings.apply(ModelConfig.newBuilder().withApiKey("test-key")).build();

    var rejection =
        assertThrows(IllegalArgumentException.class, () -> model(model, config)).getMessage();

    assertTrue(rejection.startsWith("Model " + model.id() + " does not accept " + rule), rejection);
  }
}
