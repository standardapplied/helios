/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import static com.standardapplied.helios.anthropic.AnthropicFixture.model;
import static com.standardapplied.helios.anthropic.AnthropicFixture.requestFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.anthropic.api.AnthropicJson;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Reasoning;
import com.standardapplied.helios.core.model.Reasoning.Display;
import com.standardapplied.helios.core.model.Reasoning.Level;
import com.standardapplied.helios.core.test.Golden;
import com.standardapplied.helios.core.test.ModelHarness;
import com.standardapplied.helios.core.test.ReasoningMatrix;
import com.standardapplied.helios.core.test.ReasoningMatrix.Accepts;
import com.standardapplied.helios.core.test.StubHttpServer;
import java.util.EnumSet;
import java.util.List;
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
 * Every catalogued Claude model against {@code Reasoning.Off} and every level and display: each
 * cell sends exactly the fields Anthropic documents for the model, or is rejected when the model is
 * created. The expectations are written out here from the documentation, not read from the
 * catalogue.
 */
class AnthropicReasoningTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final String PROGRESS_BETA = "thinking-display-updates-2026-08-18";

  private static final Set<Level> LOW_TO_MAX = EnumSet.range(Level.LOW, Level.MAX);

  private static final Set<Display> WITH_PROGRESS = EnumSet.allOf(Display.class);

  private static final Set<Display> WITHOUT_PROGRESS = EnumSet.of(Display.HIDDEN, Display.SUMMARY);

  private static final Map<Display, String> DISPLAY =
      Map.of(Display.HIDDEN, "omitted", Display.SUMMARY, "summarized", Display.PROGRESS, "updates");

  private static final Documented ALWAYS_ON_WITH_PROGRESS =
      new Documented(null, LOW_TO_MAX, WITH_PROGRESS);

  private static final Documented OFF_OMITTED =
      new Documented(Map.of(), LOW_TO_MAX, WITHOUT_PROGRESS);

  private static final Documented OFF_DISABLED =
      new Documented(Map.of("type", "disabled"), LOW_TO_MAX, WITHOUT_PROGRESS);

  private static final Documented CLAUDE_4_6 =
      new Documented(
          Map.of(), EnumSet.of(Level.LOW, Level.MEDIUM, Level.HIGH, Level.MAX), WITHOUT_PROGRESS);

  private static final Map<AnthropicModelId, Documented> DOCUMENTED =
      Map.ofEntries(
          Map.entry(AnthropicModelId.CLAUDE_FABLE_5_1, ALWAYS_ON_WITH_PROGRESS),
          Map.entry(AnthropicModelId.CLAUDE_MYTHOS_5_1, ALWAYS_ON_WITH_PROGRESS),
          Map.entry(AnthropicModelId.CLAUDE_FABLE_5, ALWAYS_ON_WITH_PROGRESS),
          Map.entry(AnthropicModelId.CLAUDE_OPUS_5_5, ALWAYS_ON_WITH_PROGRESS),
          Map.entry(
              AnthropicModelId.CLAUDE_MYTHOS_5, new Documented(null, LOW_TO_MAX, WITHOUT_PROGRESS)),
          Map.entry(
              AnthropicModelId.CLAUDE_SONNET_5_5,
              new Documented(Map.of("type", "between_tools"), LOW_TO_MAX, WITH_PROGRESS)),
          Map.entry(AnthropicModelId.CLAUDE_OPUS_5, OFF_DISABLED),
          Map.entry(AnthropicModelId.CLAUDE_SONNET_5, OFF_DISABLED),
          Map.entry(AnthropicModelId.CLAUDE_OPUS_4_8, OFF_OMITTED),
          Map.entry(AnthropicModelId.CLAUDE_OPUS_4_7, OFF_OMITTED),
          Map.entry(AnthropicModelId.CLAUDE_OPUS_4_6, CLAUDE_4_6),
          Map.entry(AnthropicModelId.CLAUDE_SONNET_4_6, CLAUDE_4_6),
          Map.entry(
              AnthropicModelId.CLAUDE_HAIKU_4_5, new Documented(Map.of(), Set.of(), Set.of())));

  /**
   * What a model accepts and how its off is spelled.
   *
   * @param off the {@code thinking} field sent for {@code Reasoning.Off}: empty when it is left
   *     out, {@code null} when off is rejected
   * @param levels the accepted levels
   * @param displays the accepted displays
   */
  private record Documented(Map<String, Object> off, Set<Level> levels, Set<Display> displays) {

    Accepts accepts() {
      return new Accepts(off != null, levels, displays);
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

  @Test
  void theTableCoversEveryCataloguedModel() {
    assertEquals(EnumSet.allOf(AnthropicModelId.class), EnumSet.copyOf(DOCUMENTED.keySet()));
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("acceptedCells")
  void anAcceptedCellSendsExactlyItsDocumentedFields(AnthropicModelId model, Reasoning reasoning) {
    var config = config(reasoning);
    model(model, config).close();

    var body = wire(model.id(), config);

    assertEquals(thinking(DOCUMENTED.get(model), reasoning), body.get("thinking"));
    assertEquals(outputConfig(reasoning), body.get("output_config"));
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("rejectedCells")
  void aRejectedCellThrowsAtConstructionNamingTheModelAndWhatItAccepts(
      AnthropicModelId model, Reasoning reasoning) {
    var config = config(reasoning);

    var rejection =
        assertThrows(IllegalArgumentException.class, () -> model(model, config)).getMessage();

    assertTrue(rejection.contains("Model " + model.id() + " "), rejection);
    assertTrue(rejection.contains(DOCUMENTED.get(model).accepts().description()), rejection);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"claude-opus-5-5", "claude-haiku-4-5", "claude-sonnet-4-6", "claude-opus-6"})
  void anAbsentReasoningSendsNoThinkingOrEffortField(String modelId) {
    var body = wire(modelId, ModelConfig.newBuilder().withApiKey("test-key").build());

    assertFalse(body.containsKey("thinking"), body::toString);
    assertFalse(body.containsKey("output_config"), body::toString);
  }

  @Test
  void anAbsentReasoningSendsNoThinkingOrEffortFieldOnAnyCataloguedModel() {
    for (var model : AnthropicModelId.values()) {
      anAbsentReasoningSendsNoThinkingOrEffortField(model.id());
    }
  }

  @Test
  void offIsRejectedOnTheModelsThatAlwaysThinkAndIsBareBetweenToolsOnSonnet55() {
    for (var model : List.of(AnthropicModelId.CLAUDE_OPUS_5_5, AnthropicModelId.CLAUDE_FABLE_5_1)) {
      assertThrows(IllegalArgumentException.class, () -> model(model, config(new Reasoning.Off())));
    }

    var sonnet = wire("claude-sonnet-5-5", config(new Reasoning.Off()));

    assertEquals(Map.of("type", "between_tools"), sonnet.get("thinking"));
    assertFalse(sonnet.containsKey("output_config"));
  }

  @Test
  void haiku45AcceptsNoEffortAndSendsNoThinkingFieldForOff() {
    var effort = config(new Reasoning.Effort(Level.LOW, Display.HIDDEN));

    var rejection =
        assertThrows(IllegalArgumentException.class, () -> model("claude-haiku-4-5", effort));

    assertEquals(
        "Model claude-haiku-4-5 does not accept Reasoning.Level.LOW; it accepts Off and no Effort.",
        rejection.getMessage());
    assertNull(wire("claude-haiku-4-5", config(new Reasoning.Off())).get("thinking"));
  }

  @Test
  void aDatedSnapshotIsJudgedByItsFamilysRules() {
    var effort = config(new Reasoning.Effort(Level.LOW, Display.SUMMARY));

    assertThrows(IllegalArgumentException.class, () -> model("claude-haiku-4-5-20251001", effort));
  }

  @Test
  void anUncataloguedModelIsSentEveryReasoningInTheCurrentShape() {
    assertEquals(
        Map.of("type", "disabled"),
        wire("claude-opus-6", config(new Reasoning.Off())).get("thinking"));
    ReasoningMatrix.everyReasoning()
        .filter(Reasoning.Effort.class::isInstance)
        .forEach(
            reasoning -> {
              var body = wire("claude-opus-6", config(reasoning));

              assertEquals(thinking(null, reasoning), body.get("thinking"), reasoning::toString);
              assertEquals(outputConfig(reasoning), body.get("output_config"));
            });
  }

  @Test
  void progressOnOpus55SendsUpdatesWithTheBetaTheCallerNeverNamed() {
    var progress = new Reasoning.Effort(Level.MEDIUM, Display.PROGRESS);

    var request = exchange("claude-opus-5-5", builder(progress));

    assertEquals(PROGRESS_BETA, request.headers().get("anthropic-beta"));
    assertEquals(
        Map.of("type", "adaptive", "display", "updates"),
        JSON.readValue(request.body(), Map.class).get("thinking"));
  }

  @Test
  void progressJoinsTheBetasTheCallerAlreadySends() {
    var progress = new Reasoning.Effort(Level.MEDIUM, Display.PROGRESS);

    var request =
        exchange("claude-opus-5-5", builder(progress).withHeader("Anthropic-Beta", "own-beta"));

    assertEquals("own-beta," + PROGRESS_BETA, request.headers().get("anthropic-beta"));
  }

  @Test
  void noOtherDisplayAndNoAbsentReasoningAddsTheBeta() {
    for (var display : List.of(Display.HIDDEN, Display.SUMMARY)) {
      var request =
          exchange("claude-opus-5-5", builder(new Reasoning.Effort(Level.MEDIUM, display)));

      assertNull(request.headers().get("anthropic-beta"), display::toString);
    }
    var absent = exchange("claude-opus-5-5", ModelConfig.newBuilder().withApiKey("test-key"));
    var own =
        exchange(
            "claude-opus-5-5",
            builder(new Reasoning.Effort(Level.MEDIUM, Display.SUMMARY))
                .withHeader("anthropic-beta", "own-beta"));

    assertNull(absent.headers().get("anthropic-beta"));
    assertEquals("own-beta", own.headers().get("anthropic-beta"));
  }

  private static Map<String, Object> thinking(Documented documented, Reasoning reasoning) {
    return switch (reasoning) {
      case Reasoning.Off _ when documented == null -> Map.of("type", "disabled");
      case Reasoning.Off _ -> documented.off().isEmpty() ? null : documented.off();
      case Reasoning.Effort e -> Map.of("type", "adaptive", "display", DISPLAY.get(e.display()));
    };
  }

  private static Map<String, Object> outputConfig(Reasoning reasoning) {
    return reasoning instanceof Reasoning.Effort e
        ? Map.of("effort", e.level().name().toLowerCase(Locale.ROOT))
        : null;
  }

  private static StubHttpServer.Request exchange(String modelId, ModelConfig.Builder builder) {
    return ModelHarness.exchange(
            List.of(Golden.read("anthropic/requests-reply.sse")),
            uri ->
                new AnthropicProvider()
                    .create(modelId, builder.withBaseUrl(uri + "/v1/messages").build()),
            model -> model.chat(List.of(Message.user("Hi"))))
        .getLast();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> wire(String modelId, ModelConfig config) {
    var request = requestFor(modelId, config);
    return JSON.readValue(AnthropicJson.LENIENT.writeValueAsString(request), Map.class);
  }

  private static ModelConfig config(Reasoning reasoning) {
    return builder(reasoning).build();
  }

  private static ModelConfig.Builder builder(Reasoning reasoning) {
    return ModelConfig.newBuilder().withApiKey("test-key").withReasoning(reasoning);
  }
}
