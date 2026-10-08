/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Reasoning;
import com.standardapplied.helios.core.model.Reasoning.Display;
import com.standardapplied.helios.core.model.Reasoning.Level;
import com.standardapplied.helios.gemini.api.GeminiJson;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every catalogued Gemini model against {@code Reasoning.Off} and every level and display: each
 * cell sends exactly the thinking level and summaries setting Google documents for the model, or is
 * rejected when the model is created. The expectations are written out here from the documentation,
 * not read from the catalogue.
 */
class GeminiReasoningTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final Set<Level> MINIMAL_TO_HIGH = EnumSet.range(Level.MINIMAL, Level.HIGH);

  private static final Set<Level> LOW_TO_HIGH = EnumSet.range(Level.LOW, Level.HIGH);

  private static final Set<Display> DISPLAYS = EnumSet.of(Display.HIDDEN, Display.SUMMARY);

  private static final Map<Display, String> SUMMARIES =
      Map.of(Display.HIDDEN, "none", Display.SUMMARY, "auto");

  /** The thinking levels each model documents; no Gemini 3.x model can turn thinking off. */
  private static final Map<GeminiModelId, Set<Level>> DOCUMENTED =
      Map.of(
          GeminiModelId.GEMINI_3_FLASH_PREVIEW, MINIMAL_TO_HIGH,
          GeminiModelId.GEMINI_3_1_PRO_PREVIEW, LOW_TO_HIGH,
          GeminiModelId.GEMINI_3_1_FLASH_LITE, MINIMAL_TO_HIGH,
          GeminiModelId.GEMINI_3_5_FLASH, MINIMAL_TO_HIGH,
          GeminiModelId.GEMINI_3_5_FLASH_LITE, MINIMAL_TO_HIGH,
          GeminiModelId.GEMINI_3_6_FLASH, MINIMAL_TO_HIGH,
          GeminiModelId.GEMINI_3_7_FLASH, LOW_TO_HIGH,
          GeminiModelId.GEMINI_3_8_FLASH, LOW_TO_HIGH);

  static Stream<Reasoning> everyReasoning() {
    var efforts =
        Arrays.stream(Level.values())
            .flatMap(
                level ->
                    Arrays.stream(Display.values())
                        .map(display -> (Reasoning) new Reasoning.Effort(level, display)));
    return Stream.concat(Stream.of(new Reasoning.Off()), efforts);
  }

  static Stream<Arguments> acceptedCells() {
    return cells(true);
  }

  static Stream<Arguments> rejectedCells() {
    return cells(false);
  }

  private static Stream<Arguments> cells(boolean accepted) {
    return DOCUMENTED.entrySet().stream()
        .flatMap(
            row ->
                everyReasoning()
                    .filter(reasoning -> accepts(row.getValue(), reasoning) == accepted)
                    .map(reasoning -> Arguments.of(row.getKey(), reasoning)));
  }

  private static boolean accepts(Set<Level> levels, Reasoning reasoning) {
    return reasoning instanceof Reasoning.Effort e
        && levels.contains(e.level())
        && DISPLAYS.contains(e.display());
  }

  @Test
  void theTableCoversEveryCataloguedModel() {
    assertEquals(EnumSet.allOf(GeminiModelId.class), EnumSet.copyOf(DOCUMENTED.keySet()));
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("acceptedCells")
  void anAcceptedCellSendsExactlyItsLevelAndSummaries(
      GeminiModelId model, Reasoning.Effort effort) {
    var config = config(effort);
    new GeminiProvider().create(model.id(), config).close();

    var generation = generationConfig(model, config);

    assertEquals(effort.level().name().toLowerCase(Locale.ROOT), generation.get("thinking_level"));
    assertEquals(SUMMARIES.get(effort.display()), generation.get("thinking_summaries"));
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("rejectedCells")
  void aRejectedCellThrowsAtConstructionNamingTheModelAndWhatItAccepts(
      GeminiModelId model, Reasoning reasoning) {
    var config = config(reasoning);

    var rejection =
        assertThrows(
                IllegalArgumentException.class,
                () -> new GeminiProvider().create(model.id(), config))
            .getMessage();

    assertTrue(rejection.contains("Model " + model.id() + " "), rejection);
    assertTrue(
        rejection.contains(
            "it accepts no Off and Effort " + DOCUMENTED.get(model) + " with display " + DISPLAYS),
        rejection);
  }

  @ParameterizedTest
  @EnumSource(GeminiModelId.class)
  void anAbsentReasoningSendsNoThinkingField(GeminiModelId model) {
    var generation = generationConfig(model, config(null));

    assertFalse(generation.containsKey("thinking_level"), generation::toString);
    assertFalse(generation.containsKey("thinking_summaries"), generation::toString);
  }

  @Test
  void offIsRejectedBecauseGemini3CannotStopThinking() {
    var rejection =
        assertThrows(
            IllegalArgumentException.class,
            () -> new GeminiProvider().create("gemini-3.5-flash", config(new Reasoning.Off())));

    assertTrue(
        rejection.getMessage().startsWith("Model gemini-3.5-flash does not accept Reasoning.Off"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> generationConfig(GeminiModelId model, ModelConfig config) {
    var request =
        new GeminiRequestBuilder(model, config).build(List.of(Message.user("Hi")), null, null);
    var body = JSON.readValue(GeminiJson.LENIENT.writeValueAsString(request), Map.class);
    return (Map<String, Object>) body.get("generation_config");
  }

  private static ModelConfig config(Reasoning reasoning) {
    return ModelConfig.newBuilder().withApiKey("test-key").withReasoning(reasoning).build();
  }
}
