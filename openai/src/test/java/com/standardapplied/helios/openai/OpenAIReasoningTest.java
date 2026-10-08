/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.openai;

import static com.standardapplied.helios.openai.OpenAIFixture.createModel;
import static com.standardapplied.helios.openai.OpenAIFixture.requestFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Reasoning;
import com.standardapplied.helios.core.model.Reasoning.Display;
import com.standardapplied.helios.core.model.Reasoning.Level;
import com.standardapplied.helios.core.test.ReasoningMatrix;
import com.standardapplied.helios.core.test.ReasoningMatrix.Accepts;
import com.standardapplied.helios.openai.api.OpenAIJson;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every catalogued OpenAI model against no reasoning, {@code Reasoning.Off} and every level and
 * display, alone and with a sampling parameter: each cell sends exactly the fields OpenAI documents
 * for the model, or is rejected when the model is created. The expectations are written out here
 * from the documentation, not read from the catalogue.
 */
class OpenAIReasoningTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final Set<Display> DISPLAYS = EnumSet.of(Display.HIDDEN, Display.SUMMARY);

  /** When {@code temperature} and {@code top_p} are accepted. */
  private enum Sampling {
    ALWAYS,
    ONLY_WITH_OFF,
    NOT_WITH_EFFORT,
    NEVER
  }

  private static final Documented ALWAYS_REASONS =
      new Documented(null, EnumSet.range(Level.LOW, Level.MAX), DISPLAYS, Sampling.NEVER);

  private static final Documented NONE_TO_MAX =
      new Documented(
          Map.of("effort", "none"),
          EnumSet.range(Level.LOW, Level.MAX),
          DISPLAYS,
          Sampling.ONLY_WITH_OFF);

  private static final Documented NONE_BY_DEFAULT =
      new Documented(
          Map.of("effort", "none"),
          EnumSet.range(Level.LOW, Level.XHIGH),
          DISPLAYS,
          Sampling.NOT_WITH_EFFORT);

  private static final Documented NOT_REASONING =
      new Documented(Map.of(), Set.of(), Set.of(), Sampling.ALWAYS);

  private static final Documented O_SERIES =
      new Documented(null, EnumSet.range(Level.LOW, Level.HIGH), DISPLAYS, Sampling.NEVER);

  private static final Map<OpenAIModelId, Documented> DOCUMENTED =
      Map.ofEntries(
          Map.entry(OpenAIModelId.GPT_6_ASTRA, ALWAYS_REASONS),
          Map.entry(OpenAIModelId.GPT_6_1_SOL, ALWAYS_REASONS),
          Map.entry(OpenAIModelId.GPT_6_SOL, NONE_TO_MAX),
          Map.entry(OpenAIModelId.GPT_6_LUNA, NONE_TO_MAX),
          Map.entry(OpenAIModelId.GPT_5_6, NONE_TO_MAX),
          Map.entry(OpenAIModelId.GPT_5_6_SOL, NONE_TO_MAX),
          Map.entry(OpenAIModelId.GPT_5_6_TERRA, NONE_TO_MAX),
          Map.entry(OpenAIModelId.GPT_5_6_LUNA, NONE_TO_MAX),
          Map.entry(
              OpenAIModelId.GPT_5_5,
              new Documented(
                  Map.of("effort", "none"),
                  EnumSet.range(Level.LOW, Level.XHIGH),
                  DISPLAYS,
                  Sampling.ONLY_WITH_OFF)),
          Map.entry(OpenAIModelId.GPT_5_4, NONE_BY_DEFAULT),
          Map.entry(OpenAIModelId.GPT_5_4_MINI, NONE_BY_DEFAULT),
          Map.entry(OpenAIModelId.GPT_5_4_NANO, NONE_BY_DEFAULT),
          Map.entry(OpenAIModelId.GPT_4_1, NOT_REASONING),
          Map.entry(OpenAIModelId.GPT_4_1_MINI, NOT_REASONING),
          Map.entry(OpenAIModelId.GPT_4_1_NANO, NOT_REASONING),
          Map.entry(OpenAIModelId.GPT_4O, NOT_REASONING),
          Map.entry(OpenAIModelId.GPT_4O_MINI, NOT_REASONING),
          Map.entry(OpenAIModelId.O3, O_SERIES),
          Map.entry(OpenAIModelId.O4_MINI, O_SERIES));

  /**
   * What a model accepts and how its off is spelled.
   *
   * @param off the {@code reasoning} field sent for {@code Reasoning.Off}: empty when it is left
   *     out, {@code null} when off is rejected
   * @param levels the accepted levels
   * @param displays the accepted displays
   * @param sampling when sampling parameters are accepted
   */
  private record Documented(
      Map<String, Object> off, Set<Level> levels, Set<Display> displays, Sampling sampling) {

    Accepts accepts() {
      return new Accepts(off != null, levels, displays);
    }

    boolean acceptsSampling(Reasoning reasoning) {
      return switch (sampling) {
        case ALWAYS -> true;
        case ONLY_WITH_OFF -> reasoning instanceof Reasoning.Off;
        case NOT_WITH_EFFORT -> !(reasoning instanceof Reasoning.Effort);
        case NEVER -> false;
      };
    }
  }

  static Stream<Arguments> acceptedCells() {
    return cells(true);
  }

  static Stream<Arguments> rejectedCells() {
    return cells(false);
  }

  private static Stream<Arguments> cells(boolean accepted) {
    return ReasoningMatrix.cells(DOCUMENTED, Documented::accepts, accepted);
  }

  static Stream<Arguments> acceptedSamplingCells() {
    return samplingCells(true);
  }

  static Stream<Arguments> rejectedSamplingCells() {
    return samplingCells(false);
  }

  /**
   * Every model, with no reasoning and with each reasoning it accepts, and each sampling parameter,
   * where the model accepts the parameter or where it does not.
   */
  private static Stream<Arguments> samplingCells(boolean accepted) {
    return DOCUMENTED.entrySet().stream()
        .flatMap(
            row ->
                Stream.<Reasoning>of(
                        null, new Reasoning.Off(), new Reasoning.Effort(Level.LOW, Display.SUMMARY))
                    .filter(reasoning -> row.getValue().accepts().test(reasoning))
                    .filter(reasoning -> row.getValue().acceptsSampling(reasoning) == accepted)
                    .flatMap(
                        reasoning ->
                            Stream.of("temperature", "top_p")
                                .map(name -> Arguments.of(row.getKey(), reasoning, name))));
  }

  @Test
  void theTableCoversEveryCataloguedModel() {
    assertEquals(EnumSet.allOf(OpenAIModelId.class), EnumSet.copyOf(DOCUMENTED.keySet()));
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("acceptedCells")
  void anAcceptedCellSendsExactlyItsDocumentedFields(OpenAIModelId model, Reasoning reasoning) {
    var config = config(reasoning).build();
    createModel(model, config).close();

    var body = wire(model.id(), config);

    assertEquals(reasoning(DOCUMENTED.get(model), reasoning), body.get("reasoning"));
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("rejectedCells")
  void aRejectedCellThrowsAtConstructionNamingTheModelAndWhatItAccepts(
      OpenAIModelId model, Reasoning reasoning) {
    var config = config(reasoning).build();

    var rejection =
        assertThrows(IllegalArgumentException.class, () -> createModel(model, config)).getMessage();

    assertTrue(rejection.contains("Model " + model.id() + " "), rejection);
    assertTrue(rejection.contains(DOCUMENTED.get(model).accepts().description()), rejection);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("everyModel")
  void anAbsentReasoningSendsNoReasoningField(OpenAIModelId model) {
    var body = wire(model.id(), config(null).build());

    assertFalse(body.containsKey("reasoning"), body::toString);
  }

  static Stream<OpenAIModelId> everyModel() {
    return Arrays.stream(OpenAIModelId.values());
  }

  @ParameterizedTest(name = "{0} {1} {2}")
  @MethodSource("acceptedSamplingCells")
  void anAcceptedSamplingParameterIsSentAsSet(
      OpenAIModelId model, Reasoning reasoning, String parameter) {
    var config = withSampling(config(reasoning), parameter, 0.4);
    createModel(model, config).close();

    assertEquals(0.4, wire(model.id(), config).get(parameter));
  }

  @ParameterizedTest(name = "{0} {1} {2}")
  @MethodSource("rejectedSamplingCells")
  void aRejectedSamplingParameterThrowsAtConstruction(
      OpenAIModelId model, Reasoning reasoning, String parameter) {
    var config = withSampling(config(reasoning), parameter, 0.4);

    var rejection =
        assertThrows(IllegalArgumentException.class, () -> createModel(model, config)).getMessage();

    assertTrue(rejection.startsWith("Model " + model.id() + " does not accept "), rejection);
  }

  @Test
  void samplingIsRejectedNamingTheModelTheParameterAndTheRule() {
    var astra = config(null).withTopP(0.5).build();
    var sol = config(null).withTemperature(0.5).build();
    var mini = config(new Reasoning.Effort(Level.LOW, Display.HIDDEN)).withTemperature(0.5).build();

    assertEquals(
        "Model gpt-6-astra does not accept topP whenever it is set.",
        assertThrows(
                IllegalArgumentException.class, () -> createModel(OpenAIModelId.GPT_6_ASTRA, astra))
            .getMessage());
    assertEquals(
        "Model gpt-6-sol does not accept temperature unless Reasoning.Off is set.",
        assertThrows(
                IllegalArgumentException.class, () -> createModel(OpenAIModelId.GPT_6_SOL, sol))
            .getMessage());
    assertEquals(
        "Model gpt-5.4-mini does not accept temperature with Reasoning.Effort.",
        assertThrows(
                IllegalArgumentException.class, () -> createModel(OpenAIModelId.GPT_5_4_MINI, mini))
            .getMessage());
  }

  @Test
  void offIsRejectedOnGpt6Astra() {
    var off = config(new Reasoning.Off()).build();

    assertThrows(IllegalArgumentException.class, () -> createModel(OpenAIModelId.GPT_6_ASTRA, off));
  }

  @ParameterizedTest
  @ValueSource(strings = {"MINIMAL", "MAX"})
  void anUncataloguedModelIsSentEveryLevelAndItsSampling(String level) {
    var effort = new Reasoning.Effort(Level.valueOf(level), Display.SUMMARY);
    var config = config(effort).withTemperature(0.4).withTopP(0.8).build();

    var body = wire("gpt-7", config);

    assertEquals(
        Map.of("effort", level.toLowerCase(Locale.ROOT), "summary", "auto"), body.get("reasoning"));
    assertEquals(0.4, body.get("temperature"));
    assertEquals(0.8, body.get("top_p"));
    assertEquals(
        Map.of("effort", "none"),
        wire("gpt-7", config(new Reasoning.Off()).build()).get("reasoning"));
  }

  @Test
  void progressHasNoOpenAiSpellingEvenOnAnUncataloguedModel() {
    var progress = config(new Reasoning.Effort(Level.LOW, Display.PROGRESS)).build();

    assertThrows(
        IllegalArgumentException.class, () -> new OpenAIRequestBuilder("gpt-7", null, progress));
  }

  private static Map<String, Object> reasoning(Documented documented, Reasoning reasoning) {
    return switch (reasoning) {
      case Reasoning.Off _ -> documented.off().isEmpty() ? null : documented.off();
      case Reasoning.Effort e when e.display() == Display.SUMMARY ->
          Map.of("effort", e.level().name().toLowerCase(Locale.ROOT), "summary", "auto");
      case Reasoning.Effort e -> Map.of("effort", e.level().name().toLowerCase(Locale.ROOT));
    };
  }

  private static ModelConfig withSampling(ModelConfig.Builder builder, String name, double value) {
    return ("temperature".equals(name) ? builder.withTemperature(value) : builder.withTopP(value))
        .build();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> wire(String modelId, ModelConfig config) {
    var request = requestFor(modelId, config);
    return JSON.readValue(OpenAIJson.LENIENT.writeValueAsString(request), Map.class);
  }

  private static ModelConfig.Builder config(Reasoning reasoning) {
    return ModelConfig.newBuilder()
        .withApiKey("test-key")
        .withBaseUrl("http://127.0.0.1:1/v1/responses")
        .withReasoning(reasoning);
  }
}
