/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import static com.standardapplied.helios.core.test.SseEvents.data;
import static com.standardapplied.helios.gemini.GeminiSse.HELLO_DELTA;
import static com.standardapplied.helios.gemini.GeminiSse.INTERACTION_COMPLETED;
import static com.standardapplied.helios.gemini.GeminiSse.MODEL_OUTPUT_START;
import static com.standardapplied.helios.gemini.GeminiSse.MODEL_OUTPUT_STOP;
import static com.standardapplied.helios.gemini.GeminiSse.TEXT_FLOW;
import static com.standardapplied.helios.gemini.GeminiSse.drain;
import static com.standardapplied.helios.gemini.GeminiSse.stepArgumentsDelta;
import static com.standardapplied.helios.gemini.GeminiSse.stepDelta;
import static com.standardapplied.helios.gemini.GeminiSse.stepStart;
import static com.standardapplied.helios.gemini.GeminiSse.stepStop;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.StreamEvent;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The steps of a Gemini stream, from {@code step.start} through {@code step.delta} to {@code
 * step.stop}: model output text, function calls and thoughts.
 */
class StreamedStepsTest {

  private static final String GET_WEATHER_WITH_ARGUMENTS =
      "{\"type\":\"function_call\",\"id\":\"call_1\",\"name\":\"get_weather\","
          + "\"arguments\":{\"city\":\"NYC\"}}";

  @Test
  void functionCallStreamedViaArgumentsDeltas() {
    var sse =
        stepStart(1, "{\"type\":\"function_call\",\"id\":\"call_1\",\"name\":\"get_weather\"}")
            + stepArgumentsDelta(1, "{\\\"city\\\":\\\"")
            + stepArgumentsDelta(1, "NYC\\\"}")
            + stepStop(1)
            + INTERACTION_COMPLETED;
    var events = drain(sse);
    assertEquals(2, events.size());
    assertInstanceOf(StreamEvent.ToolCallComplete.class, events.get(0));
    var tc = ((StreamEvent.ToolCallComplete) events.get(0)).toolCall();
    assertEquals("get_weather", tc.name());
    assertEquals("call_1", tc.id());
    assertEquals(Map.of("city", "NYC"), tc.arguments());

    var done = (StreamEvent.Done) events.get(1);
    assertEquals(FinishReason.TOOL_CALLS, done.response().finishReason());
    assertFalse(done.response().toolCalls().isEmpty());
  }

  @Test
  void functionCallWithoutAnyArgsYieldsEmptyMap() {
    var sse =
        stepStart(1, "{\"type\":\"function_call\",\"id\":\"call_1\",\"name\":\"get_weather\"}")
            + stepStop(1)
            + INTERACTION_COMPLETED;
    var events = drain(sse);
    var tc = ((StreamEvent.ToolCallComplete) events.get(0)).toolCall();
    assertEquals(Map.of(), tc.arguments());
  }

  @Test
  void thoughtStepCapturesSignatureAndSummary() {
    var thought =
        "{\"type\":\"thought\",\"signature\":\"sig123\","
            + "\"summary\":[{\"type\":\"text\",\"text\":\"thinking...\"}]}";
    var sse = stepStart(0, thought) + stepStop(0) + TEXT_FLOW;
    var events = drain(sse);

    var done = (StreamEvent.Done) events.getLast();
    assertNotNull(done.response().thinking());
    assertTrue(done.response().thinking().contains("thinking..."));

    var metadata = done.response().metadata();
    assertTrue(metadata.containsKey(GeminiResponseAssembler.THOUGHT_SIGNATURES_KEY));
    assertEquals("sig123", metadata.get(GeminiResponseAssembler.THOUGHT_SIGNATURES_KEY));
  }

  @Test
  void thoughtStepWithoutSummaryStillCapturesSignature() {
    var sse =
        stepStart(0, "{\"type\":\"thought\",\"signature\":\"sig456\"}")
            + stepStop(0)
            + MODEL_OUTPUT_START
            + HELLO_DELTA
            + MODEL_OUTPUT_STOP
            + INTERACTION_COMPLETED;
    var events = drain(sse);
    var done = (StreamEvent.Done) events.getLast();
    var metadata = done.response().metadata();
    assertEquals("sig456", metadata.get(GeminiResponseAssembler.THOUGHT_SIGNATURES_KEY));
    assertNull(done.response().thinking());
  }

  @Test
  void thoughtSignatureDeliveredAsStepDeltaIsCaptured() {
    // Live Gemini 3.x wire shape (probed 2026-05-13): step.start carries only {"type":"thought"},
    // and the signature arrives as a step.delta whose delta is {"type":"thought_signature",
    // "signature":"..."}. The legacy fixture shape (signature on step.start) is also still
    // exercised by thoughtStepCapturesSignatureAndSummary — both paths must work.
    var thoughtStart = stepStart(0, "{\"type\":\"thought\"}");
    var sigDelta = stepDelta(0, "{\"type\":\"thought_signature\",\"signature\":\"wireSig\"}");
    var sse =
        thoughtStart
            + sigDelta
            + stepStop(0)
            + MODEL_OUTPUT_START
            + HELLO_DELTA
            + MODEL_OUTPUT_STOP
            + INTERACTION_COMPLETED;
    var events = drain(sse);
    var done = (StreamEvent.Done) events.getLast();
    var metadata = done.response().metadata();
    assertTrue(
        metadata.containsKey(GeminiResponseAssembler.THOUGHT_SIGNATURES_KEY),
        "Expected thought_signature step.delta to be captured");
    assertEquals("wireSig", metadata.get(GeminiResponseAssembler.THOUGHT_SIGNATURES_KEY));
  }

  @Test
  void thoughtDeltaTextFoldsIntoThinking() {
    var thoughtStart = stepStart(0, "{\"type\":\"thought\",\"signature\":\"sig\"}");
    var thoughtDelta = stepDelta(0, "{\"type\":\"text\",\"text\":\"deeper\"}");
    var sse =
        thoughtStart
            + thoughtDelta
            + stepStop(0)
            + MODEL_OUTPUT_START
            + HELLO_DELTA
            + MODEL_OUTPUT_STOP
            + INTERACTION_COMPLETED;
    var events = drain(sse);
    var textDeltas = events.stream().filter(e -> e instanceof StreamEvent.TextDelta).count();
    assertEquals(1, textDeltas, "thought delta text must NOT surface as a TextDelta");

    // Streaming surface: thought delta text arrives as ThinkingDelta, step.stop emits
    // ThinkingComplete with the assembled text and signature.
    var thinkingDelta =
        events.stream()
            .filter(StreamEvent.ThinkingDelta.class::isInstance)
            .map(StreamEvent.ThinkingDelta.class::cast)
            .findFirst()
            .orElseThrow();
    assertEquals("deeper", thinkingDelta.text());

    var thinkingComplete =
        events.stream()
            .filter(StreamEvent.ThinkingComplete.class::isInstance)
            .map(StreamEvent.ThinkingComplete.class::cast)
            .findFirst()
            .orElseThrow();
    assertTrue(thinkingComplete.fullThinking().contains("deeper"));
    assertEquals("sig", thinkingComplete.signature());

    var done = (StreamEvent.Done) events.getLast();
    assertNotNull(done.response().thinking());
    assertTrue(done.response().thinking().contains("deeper"));
  }

  @Test
  void thoughtSummaryDeltaTextStreamsAsThinking() {
    var thoughtStart = stepStart(0, "{\"type\":\"thought\"}");
    var summary =
        stepDelta(
            0,
            "{\"type\":\"thought_summary\","
                + "\"content\":{\"type\":\"text\",\"text\":\"Checking the arithmetic.\"}}");
    var signature = stepDelta(0, "{\"type\":\"thought_signature\",\"signature\":\"sig\"}");

    var events = drain(thoughtStart + summary + signature + stepStop(0) + TEXT_FLOW);

    assertEquals(
        "Checking the arithmetic.",
        events.stream()
            .filter(StreamEvent.ThinkingDelta.class::isInstance)
            .map(StreamEvent.ThinkingDelta.class::cast)
            .findFirst()
            .orElseThrow()
            .text());
    var complete =
        events.stream()
            .filter(StreamEvent.ThinkingComplete.class::isInstance)
            .map(StreamEvent.ThinkingComplete.class::cast)
            .findFirst()
            .orElseThrow();
    assertEquals("Checking the arithmetic.", complete.fullThinking());
    assertEquals("sig", complete.signature());
    var done = (StreamEvent.Done) events.getLast();
    assertEquals("Checking the arithmetic.", done.response().thinking());
  }

  @Test
  void eachSummaryDeltaAddsAnItemAfterTheSummaryAThoughtStartsWith() {
    var thoughtStart =
        stepStart(
            0, "{\"type\":\"thought\",\"summary\":[{\"type\":\"text\",\"text\":\"First.\"}]}");
    var second =
        stepDelta(
            0,
            "{\"type\":\"thought_summary\",\"content\":{\"type\":\"text\",\"text\":\"Second.\"}}");
    var third =
        stepDelta(
            0,
            "{\"type\":\"thought_summary\",\"content\":{\"type\":\"text\",\"text\":\"Third.\"}}");

    var events = drain(thoughtStart + second + third + stepStop(0) + TEXT_FLOW);

    assertEquals(
        List.of("Second.", "Third."),
        events.stream()
            .filter(StreamEvent.ThinkingDelta.class::isInstance)
            .map(event -> ((StreamEvent.ThinkingDelta) event).text())
            .toList());
    assertEquals(
        "First.\nSecond.\nThird.", ((StreamEvent.Done) events.getLast()).response().thinking());
  }

  @ParameterizedTest
  @MethodSource("summaryDeltasWithoutText")
  void aThoughtSummaryDeltaWithoutTextIsIgnored(String delta) {
    var events =
        drain(
            stepStart(0, "{\"type\":\"thought\"}") + stepDelta(0, delta) + stepStop(0) + TEXT_FLOW);

    assertTrue(events.stream().noneMatch(StreamEvent.ThinkingDelta.class::isInstance));
    assertTrue(events.stream().noneMatch(StreamEvent.Error.class::isInstance));
    assertNull(((StreamEvent.Done) events.getLast()).response().thinking());
  }

  static Stream<Named<String>> summaryDeltasWithoutText() {
    return Stream.of(
        named("no content", "{\"type\":\"thought_summary\"}"),
        named(
            "non-text content",
            "{\"type\":\"thought_summary\",\"content\":{\"type\":\"image\",\"data\":\"x\"}}"),
        named(
            "non-text content carrying text",
            "{\"type\":\"thought_summary\",\"content\":{\"type\":\"image\",\"text\":\"alt\"}}"),
        named(
            "content without text",
            "{\"type\":\"thought_summary\",\"content\":{\"type\":\"text\"}}"));
  }

  @Test
  void thoughtWithNoSignatureFieldStillCapturesSummary() {
    var thought = "{\"type\":\"thought\",\"summary\":[{\"type\":\"text\",\"text\":\"inner\"}]}";
    var sse =
        stepStart(0, thought)
            + stepStop(0)
            + MODEL_OUTPUT_START
            + HELLO_DELTA
            + MODEL_OUTPUT_STOP
            + INTERACTION_COMPLETED;
    var events = drain(sse);
    var done = (StreamEvent.Done) events.getLast();
    assertEquals("inner", done.response().thinking());
    assertFalse(
        done.response().metadata().containsKey(GeminiResponseAssembler.THOUGHT_SIGNATURES_KEY));
  }

  @Test
  void multipleTextDeltas() {
    var sse =
        MODEL_OUTPUT_START
            + stepDelta(0, "{\"type\":\"text\",\"text\":\"Hello \"}")
            + stepDelta(0, "{\"type\":\"text\",\"text\":\"World\"}")
            + MODEL_OUTPUT_STOP
            + INTERACTION_COMPLETED;
    var events = drain(sse);
    assertEquals(3, events.size());
    var done = (StreamEvent.Done) events.getLast();
    assertEquals("Hello World", done.response().content());
  }

  @Test
  void textDeltaWithMissingTextFieldIsSilent() {
    var noText = stepDelta(0, "{\"type\":\"text\"}");
    var sse = MODEL_OUTPUT_START + noText + MODEL_OUTPUT_STOP + INTERACTION_COMPLETED;
    var events = drain(sse);
    assertEquals(1, events.size());
    assertInstanceOf(StreamEvent.Done.class, events.getFirst());
    assertEquals("", ((StreamEvent.Done) events.getFirst()).response().content());
  }

  @Test
  void textDeltaForUnknownStepIndexFallsThroughToContent() {
    // step.delta without a matching step.start — state==null. Text still accumulates onto
    // contentBuilder rather than being silently dropped.
    var sse = HELLO_DELTA + MODEL_OUTPUT_STOP + INTERACTION_COMPLETED;
    var events = drain(sse);
    assertEquals(2, events.size());
    assertInstanceOf(StreamEvent.TextDelta.class, events.get(0));
    assertEquals("Hello", ((StreamEvent.Done) events.get(1)).response().content());
  }

  @Test
  void googleSearchStepsDoNotBreakParsing() {
    var searchCall = stepStart(0, "{\"type\":\"google_search_call\",\"id\":\"gs_1\"}");
    var searchResult = stepStart(1, "{\"type\":\"google_search_result\",\"call_id\":\"gs_1\"}");
    var sse =
        searchCall
            + stepStop(0)
            + searchResult
            + stepStop(1)
            + stepStart(2, "{\"type\":\"model_output\"}")
            + stepDelta(2, "{\"type\":\"text\",\"text\":\"Hello\"}")
            + stepStop(2)
            + INTERACTION_COMPLETED;
    var events = drain(sse);
    var done = (StreamEvent.Done) events.getLast();
    assertEquals("Hello", done.response().content());
    assertFalse(done.response().hasCitations());
  }

  @Test
  void groundedSearchCallStepStartIsToleratedAndIgnored() {
    // The same search-call step can instead arrive on step.start, where Step.arguments already
    // tolerates the object. It carries no model-visible output and must be silently absorbed.
    var sse =
        stepStart(
                1,
                "{\"type\":\"google_search_call\",\"id\":\"gsc_1\","
                    + "\"arguments\":{\"queries\":[\"x\"]},\"search_type\":\"web_search\"}")
            + stepStop(1)
            + TEXT_FLOW;
    var events = drain(sse);
    assertFalse(events.stream().anyMatch(e -> e instanceof StreamEvent.Error));
    var done = (StreamEvent.Done) events.getLast();
    assertEquals("Hello", done.response().content());
    assertEquals(FinishReason.STOP, done.response().finishReason());
  }

  static Stream<Named<String>> argumentsDeltasAfterAStartWithArguments() {
    return Stream.of(
        named("functionCallWithEmbeddedArgumentsOnStartFallsBack", ""),
        named(
            "functionCallWithMalformedArgsDeltaFallsBackToStartArgs",
            stepArgumentsDelta(1, "{not json")),
        named(
            "argumentsDeltaParsingNullPayloadFallsBackToStartArgs", stepArgumentsDelta(1, "null")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("argumentsDeltasAfterAStartWithArguments")
  void functionCallFallsBackToTheArgumentsOnItsStart(String argumentsDeltas) {
    var events =
        drain(
            stepStart(1, GET_WEATHER_WITH_ARGUMENTS)
                + argumentsDeltas
                + stepStop(1)
                + INTERACTION_COMPLETED);

    var tc = ((StreamEvent.ToolCallComplete) events.get(0)).toolCall();
    assertEquals(Map.of("city", "NYC"), tc.arguments());
  }

  static Stream<Named<String>> thoughtsWithAnEmptySignature() {
    return Stream.of(
        named(
            "thoughtSignatureDeltaWithEmptySignatureIsIgnored",
            stepStart(0, "{\"type\":\"thought\"}")
                + stepDelta(0, "{\"type\":\"thought_signature\",\"signature\":\"\"}")),
        named(
            "thoughtWithEmptySignatureIsIgnored",
            stepStart(0, "{\"type\":\"thought\",\"signature\":\"\"}")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("thoughtsWithAnEmptySignature")
  void anEmptyThoughtSignatureIsIgnored(String thought) {
    var done = (StreamEvent.Done) drain(thought + stepStop(0) + TEXT_FLOW).getLast();

    assertFalse(
        done.response().metadata().containsKey(GeminiResponseAssembler.THOUGHT_SIGNATURES_KEY));
  }

  static Stream<Arguments> thoughtSummaries() {
    return Stream.of(
        arguments(
            named(
                "thoughtSummaryItemsWithEmptyOrNonTextSkipped",
                "[{\"type\":\"text\",\"text\":\"\"},"
                    + "{\"type\":\"image\",\"mime_type\":\"image/png\",\"data\":\"x\"},"
                    + "{\"type\":\"text\",\"text\":\"keep\"}]"),
            "keep"),
        arguments(
            named(
                "multipleThoughtSummaryItemsConcatenateThinking",
                "[{\"type\":\"text\",\"text\":\"first\"},"
                    + "{\"type\":\"text\",\"text\":\"second\"}]"),
            "first\nsecond"),
        arguments(
            named(
                "thoughtSummaryItemWithNullTextIsSkipped",
                "[{\"type\":\"text\"},{\"type\":\"text\",\"text\":\"only\"}]"),
            "only"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("thoughtSummaries")
  void thinkingIsTheTextOfTheSummaryItems(String summary, String thinking) {
    var thought = "{\"type\":\"thought\",\"signature\":\"sig\",\"summary\":" + summary + "}";

    var done = (StreamEvent.Done) drain(stepStart(0, thought) + stepStop(0) + TEXT_FLOW).getLast();

    assertEquals(thinking, done.response().thinking());
  }

  static Stream<Named<String>> stepEventsWithoutAStep() {
    return Stream.of(
        named(
            "stepStartWithoutIndexIsIgnored",
            data("{\"event_type\":\"step.start\",\"step\":{\"type\":\"model_output\"}}")),
        named("stepStartWithoutStepIsIgnored", data("{\"event_type\":\"step.start\",\"index\":0}")),
        named(
            "stepDeltaWithoutIndexIsIgnored",
            data("{\"event_type\":\"step.delta\",\"delta\":{\"type\":\"text\",\"text\":\"x\"}}")),
        named("stepStopWithoutIndexIsIgnored", data("{\"event_type\":\"step.stop\"}")),
        named(
            "stepStopForUnknownIndexIsIgnored",
            data("{\"event_type\":\"step.stop\",\"index\":42}")),
        named("argumentsDeltaWithoutPriorStartIsIgnored", stepArgumentsDelta(7, "{}")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("stepEventsWithoutAStep")
  void aStepEventWithoutItsStepIsIgnored(String stepEvent) {
    assertEquals(2, drain(stepEvent + TEXT_FLOW).size());
  }

  static Stream<Named<String>> deltasThatCarryNoText() {
    return Stream.of(
        named(
            "stepDeltaWithoutDeltaOrArgumentsDeltaIsNoOp",
            data("{\"event_type\":\"step.delta\",\"index\":0}")),
        named("argumentsDeltaForNonFunctionCallStepIsIgnored", stepArgumentsDelta(0, "{}")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("deltasThatCarryNoText")
  void aModelOutputDeltaWithoutTextIsIgnored(String delta) {
    var events =
        drain(MODEL_OUTPUT_START + delta + HELLO_DELTA + MODEL_OUTPUT_STOP + INTERACTION_COMPLETED);

    assertEquals(2, events.size());
    assertInstanceOf(StreamEvent.TextDelta.class, events.get(0));
    assertEquals(
        "Hello", ((StreamEvent.Done) events.get(1)).response().content(), "no arg pollution");
  }
}
