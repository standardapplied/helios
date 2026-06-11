/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.StreamEvent;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLSession;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

class StreamingIteratorTest {

  private static final Duration SHORT_IDLE_TIMEOUT = Duration.ofMillis(200);

  private static String stepStart(int index, String stepJson) {
    return "data: {\"event_type\":\"step.start\",\"index\":"
        + index
        + ",\"step\":"
        + stepJson
        + "}\n\n";
  }

  private static String stepDelta(int index, String deltaJson) {
    return "data: {\"event_type\":\"step.delta\",\"index\":"
        + index
        + ",\"delta\":"
        + deltaJson
        + "}\n\n";
  }

  private static String stepArgumentsDelta(int index, String escapedJson) {
    return "data: {\"event_type\":\"step.delta\",\"index\":"
        + index
        + ",\"arguments_delta\":\""
        + escapedJson
        + "\"}\n\n";
  }

  private static String stepStop(int index) {
    return "data: {\"event_type\":\"step.stop\",\"index\":" + index + ",\"status\":\"done\"}\n\n";
  }

  private static final String INTERACTION_CREATED =
      "data: {\"event_type\":\"interaction.created\","
          + "\"interaction\":{\"id\":\"int_xyz\",\"status\":\"in_progress\"}}\n\n";

  private static final String INTERACTION_IN_PROGRESS =
      "data: {\"event_type\":\"interaction.in_progress\",\"interaction_id\":\"int_xyz\"}\n\n";

  private static final String INTERACTION_COMPLETED =
      "data: {\"event_type\":\"interaction.completed\","
          + "\"interaction\":{\"id\":\"int_xyz\",\"status\":\"completed\","
          + "\"usage\":{\"total_input_tokens\":10,\"total_output_tokens\":5,\"total_tokens\":15}}}\n\n";

  private static final String INTERACTION_COMPLETED_NO_USAGE =
      "data: {\"event_type\":\"interaction.completed\","
          + "\"interaction\":{\"id\":\"int_xyz\",\"status\":\"completed\"}}\n\n";

  private static final String INTERACTION_FAILED =
      "data: {\"event_type\":\"interaction.completed\","
          + "\"interaction\":{\"id\":\"int_xyz\",\"status\":\"failed\","
          + "\"usage\":{\"total_input_tokens\":10,\"total_output_tokens\":5,\"total_tokens\":15}}}\n\n";

  private static final String MODEL_OUTPUT_START = stepStart(0, "{\"type\":\"model_output\"}");
  private static final String HELLO_DELTA = stepDelta(0, "{\"type\":\"text\",\"text\":\"Hello\"}");
  private static final String MODEL_OUTPUT_STOP = stepStop(0);

  private static final String TEXT_FLOW =
      MODEL_OUTPUT_START + HELLO_DELTA + MODEL_OUTPUT_STOP + INTERACTION_COMPLETED;

  private final tools.jackson.databind.ObjectMapper objectMapper =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  @org.junit.jupiter.api.Test
  void textDeltaEvents() {
    try (var iterator = createIterator(TEXT_FLOW, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(2, events.size());
      assertInstanceOf(StreamEvent.TextDelta.class, events.get(0));
      assertEquals("Hello", ((StreamEvent.TextDelta) events.get(0)).text());
      assertInstanceOf(StreamEvent.Done.class, events.get(1));

      var done = (StreamEvent.Done) events.get(1);
      assertEquals("Hello", done.response().content());
      assertEquals(FinishReason.STOP, done.response().finishReason());
      assertEquals("int_xyz", done.response().metadata().get(GeminiModel.INTERACTION_ID_KEY));
    }
  }

  @org.junit.jupiter.api.Test
  void functionCallStreamedViaArgumentsDeltas() {
    var sse =
        stepStart(1, "{\"type\":\"function_call\",\"id\":\"call_1\",\"name\":\"get_weather\"}")
            + stepArgumentsDelta(1, "{\\\"city\\\":\\\"")
            + stepArgumentsDelta(1, "NYC\\\"}")
            + stepStop(1)
            + INTERACTION_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
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
  }

  @org.junit.jupiter.api.Test
  void functionCallWithEmbeddedArgumentsOnStartFallsBack() {
    var sse =
        stepStart(
                1,
                "{\"type\":\"function_call\",\"id\":\"call_1\",\"name\":\"get_weather\","
                    + "\"arguments\":{\"city\":\"NYC\"}}")
            + stepStop(1)
            + INTERACTION_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var tc = ((StreamEvent.ToolCallComplete) events.get(0)).toolCall();
      assertEquals(Map.of("city", "NYC"), tc.arguments());
    }
  }

  @org.junit.jupiter.api.Test
  void functionCallWithMalformedArgsDeltaFallsBackToStartArgs() {
    var sse =
        stepStart(
                1,
                "{\"type\":\"function_call\",\"id\":\"call_1\",\"name\":\"get_weather\","
                    + "\"arguments\":{\"city\":\"NYC\"}}")
            + stepArgumentsDelta(1, "{not json")
            + stepStop(1)
            + INTERACTION_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var tc = ((StreamEvent.ToolCallComplete) events.get(0)).toolCall();
      assertEquals(Map.of("city", "NYC"), tc.arguments());
    }
  }

  @org.junit.jupiter.api.Test
  void functionCallWithoutAnyArgsYieldsEmptyMap() {
    var sse =
        stepStart(1, "{\"type\":\"function_call\",\"id\":\"call_1\",\"name\":\"get_weather\"}")
            + stepStop(1)
            + INTERACTION_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var tc = ((StreamEvent.ToolCallComplete) events.get(0)).toolCall();
      assertEquals(Map.of(), tc.arguments());
    }
  }

  @org.junit.jupiter.api.Test
  void thoughtStepCapturesSignatureAndSummary() {
    var thought =
        "{\"type\":\"thought\",\"signature\":\"sig123\","
            + "\"summary\":[{\"type\":\"text\",\"text\":\"thinking...\"}]}";
    var sse = stepStart(0, thought) + stepStop(0) + TEXT_FLOW;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }

      var done = (StreamEvent.Done) events.getLast();
      assertNotNull(done.response().thinking());
      assertTrue(done.response().thinking().contains("thinking..."));

      var metadata = done.response().metadata();
      assertTrue(metadata.containsKey(GeminiModel.THOUGHT_SIGNATURES_KEY));
      assertEquals("sig123", metadata.get(GeminiModel.THOUGHT_SIGNATURES_KEY));
    }
  }

  @org.junit.jupiter.api.Test
  void thoughtStepWithoutSummaryStillCapturesSignature() {
    var sse =
        stepStart(0, "{\"type\":\"thought\",\"signature\":\"sig456\"}")
            + stepStop(0)
            + MODEL_OUTPUT_START
            + HELLO_DELTA
            + MODEL_OUTPUT_STOP
            + INTERACTION_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var done = (StreamEvent.Done) events.getLast();
      var metadata = done.response().metadata();
      assertEquals("sig456", metadata.get(GeminiModel.THOUGHT_SIGNATURES_KEY));
      assertNull(done.response().thinking());
    }
  }

  @org.junit.jupiter.api.Test
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
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var done = (StreamEvent.Done) events.getLast();
      var metadata = done.response().metadata();
      assertTrue(
          metadata.containsKey(GeminiModel.THOUGHT_SIGNATURES_KEY),
          "Expected thought_signature step.delta to be captured");
      assertEquals("wireSig", metadata.get(GeminiModel.THOUGHT_SIGNATURES_KEY));
    }
  }

  @org.junit.jupiter.api.Test
  void thoughtSignatureDeltaWithEmptySignatureIsIgnored() {
    var sse =
        stepStart(0, "{\"type\":\"thought\"}")
            + stepDelta(0, "{\"type\":\"thought_signature\",\"signature\":\"\"}")
            + stepStop(0)
            + MODEL_OUTPUT_START
            + HELLO_DELTA
            + MODEL_OUTPUT_STOP
            + INTERACTION_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var done = (StreamEvent.Done) events.getLast();
      assertFalse(done.response().metadata().containsKey(GeminiModel.THOUGHT_SIGNATURES_KEY));
    }
  }

  @org.junit.jupiter.api.Test
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
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
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
  }

  @org.junit.jupiter.api.Test
  void usageFromCompleteEvent() {
    try (var iterator = createIterator(TEXT_FLOW, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var done = (StreamEvent.Done) events.getLast();
      assertNotNull(done.response().usage());
      assertEquals(10, done.response().usage().inputTokens());
      assertEquals(5, done.response().usage().outputTokens());
    }
  }

  @org.junit.jupiter.api.Test
  void usageOmittedDoesNotPopulateResponseUsage() {
    var sse = MODEL_OUTPUT_START + HELLO_DELTA + MODEL_OUTPUT_STOP + INTERACTION_COMPLETED_NO_USAGE;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var done = (StreamEvent.Done) events.getLast();
      assertNull(done.response().usage());
    }
  }

  @org.junit.jupiter.api.Test
  void failedInteractionSetsErrorFinishReason() {
    try (var iterator = createIterator(INTERACTION_FAILED, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(1, events.size());
      var done = (StreamEvent.Done) events.getFirst();
      assertEquals(FinishReason.ERROR, done.response().finishReason());
    }
  }

  @org.junit.jupiter.api.Test
  void interactionCreatedAndInProgressAreInformational() {
    var sse =
        INTERACTION_CREATED
            + INTERACTION_IN_PROGRESS
            + MODEL_OUTPUT_START
            + HELLO_DELTA
            + MODEL_OUTPUT_STOP
            + INTERACTION_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      // Only TextDelta + Done bubble out — interaction.* and step.start/stop are silent.
      assertEquals(2, events.size());
      assertInstanceOf(StreamEvent.TextDelta.class, events.get(0));
      assertInstanceOf(StreamEvent.Done.class, events.get(1));
      var done = (StreamEvent.Done) events.get(1);
      assertEquals("int_xyz", done.response().metadata().get(GeminiModel.INTERACTION_ID_KEY));
    }
  }

  @org.junit.jupiter.api.Test
  void emptyAndDoneDataLinesAreSkipped() {
    var sse = "data: \n\ndata: [DONE]\n\n" + TEXT_FLOW;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(2, events.size());
      assertInstanceOf(StreamEvent.TextDelta.class, events.get(0));
    }
  }

  @org.junit.jupiter.api.Test
  void nonDataLinesAreIgnored() {
    var sse = "event: step.delta\n" + TEXT_FLOW;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(2, events.size());
    }
  }

  @org.junit.jupiter.api.Test
  void malformedJsonEmitsErrorEvent() {
    var sse = "data: {not valid json}\n\n" + INTERACTION_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(2, events.size());
      assertInstanceOf(StreamEvent.Error.class, events.get(0));
      assertInstanceOf(StreamEvent.Done.class, events.get(1));
    }
  }

  @org.junit.jupiter.api.Test
  void unknownEventTypeIsTolerated() {
    var unknown = "data: {\"event_type\":\"interaction.unknown\"}\n\n";
    var sse = unknown + TEXT_FLOW;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(2, events.size());
      assertInstanceOf(StreamEvent.TextDelta.class, events.get(0));
    }
  }

  @org.junit.jupiter.api.Test
  void stepStartWithoutIndexIsIgnored() {
    var sse =
        "data: {\"event_type\":\"step.start\",\"step\":{\"type\":\"model_output\"}}\n\n"
            + TEXT_FLOW;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(2, events.size());
    }
  }

  @org.junit.jupiter.api.Test
  void stepStartWithoutStepIsIgnored() {
    var sse = "data: {\"event_type\":\"step.start\",\"index\":0}\n\n" + TEXT_FLOW;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(2, events.size());
    }
  }

  @org.junit.jupiter.api.Test
  void stepDeltaWithoutIndexIsIgnored() {
    var sse =
        "data: {\"event_type\":\"step.delta\",\"delta\":{\"type\":\"text\",\"text\":\"x\"}}\n\n"
            + TEXT_FLOW;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(2, events.size());
    }
  }

  @org.junit.jupiter.api.Test
  void stepDeltaWithoutDeltaOrArgumentsDeltaIsNoOp() {
    var sse =
        MODEL_OUTPUT_START
            + "data: {\"event_type\":\"step.delta\",\"index\":0}\n\n"
            + HELLO_DELTA
            + MODEL_OUTPUT_STOP
            + INTERACTION_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(2, events.size());
      assertInstanceOf(StreamEvent.TextDelta.class, events.get(0));
    }
  }

  @org.junit.jupiter.api.Test
  void stepStopWithoutIndexIsIgnored() {
    var sse = "data: {\"event_type\":\"step.stop\"}\n\n" + TEXT_FLOW;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(2, events.size());
    }
  }

  @org.junit.jupiter.api.Test
  void stepStopForUnknownIndexIsIgnored() {
    var sse = "data: {\"event_type\":\"step.stop\",\"index\":42}\n\n" + TEXT_FLOW;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(2, events.size());
    }
  }

  @org.junit.jupiter.api.Test
  void argumentsDeltaWithoutPriorStartIsIgnored() {
    var sse =
        stepArgumentsDelta(7, "{}")
            + MODEL_OUTPUT_START
            + HELLO_DELTA
            + MODEL_OUTPUT_STOP
            + INTERACTION_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(2, events.size());
    }
  }

  @org.junit.jupiter.api.Test
  void argumentsDeltaForNonFunctionCallStepIsIgnored() {
    var sse =
        MODEL_OUTPUT_START
            + stepArgumentsDelta(0, "{}")
            + HELLO_DELTA
            + MODEL_OUTPUT_STOP
            + INTERACTION_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(2, events.size());
      assertEquals(
          "Hello", ((StreamEvent.Done) events.get(1)).response().content(), "no arg pollution");
    }
  }

  @org.junit.jupiter.api.Test
  void idleTimeoutEmitsErrorEvent() throws Exception {
    var pipedIn = new PipedInputStream();
    var pipedOut = new PipedOutputStream(pipedIn);

    try (var iterator =
        new GeminiModel.StreamingIterator(
            fakeResponse(pipedIn), objectMapper, SHORT_IDLE_TIMEOUT)) {
      assertTrue(iterator.hasNext());
      var event = iterator.next();
      assertInstanceOf(StreamEvent.Error.class, event);
      var error = (StreamEvent.Error) event;
      assertTrue(error.message().contains("idle timeout"));
      assertInstanceOf(GeminiException.class, error.cause());
      assertTrue(((GeminiException) error.cause()).isRetryable());
    }
    pipedOut.close();
  }

  @org.junit.jupiter.api.Test
  void closeIsIdempotent() {
    var iterator = createIterator(TEXT_FLOW, Duration.ofSeconds(5));
    iterator.close();
    iterator.close();
    assertFalse(iterator.hasNext());
  }

  @org.junit.jupiter.api.Test
  void closeAfterPartialConsumption() {
    var sse =
        MODEL_OUTPUT_START + HELLO_DELTA + HELLO_DELTA + MODEL_OUTPUT_STOP + INTERACTION_COMPLETED;
    var iterator = createIterator(sse, Duration.ofSeconds(5));
    assertTrue(iterator.hasNext());
    iterator.next();
    iterator.close();
    assertFalse(iterator.hasNext());
  }

  @org.junit.jupiter.api.Test
  void multipleTextDeltas() {
    var sse =
        MODEL_OUTPUT_START
            + stepDelta(0, "{\"type\":\"text\",\"text\":\"Hello \"}")
            + stepDelta(0, "{\"type\":\"text\",\"text\":\"World\"}")
            + MODEL_OUTPUT_STOP
            + INTERACTION_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(3, events.size());
      var done = (StreamEvent.Done) events.getLast();
      assertEquals("Hello World", done.response().content());
    }
  }

  @org.junit.jupiter.api.Test
  void emptyStreamProducesDoneWithEmptyContent() {
    try (var iterator = createIterator(INTERACTION_COMPLETED, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(1, events.size());
      var done = (StreamEvent.Done) events.getFirst();
      assertEquals("", done.response().content());
      assertEquals(FinishReason.STOP, done.response().finishReason());
    }
  }

  @org.junit.jupiter.api.Test
  void textDeltaWithUrlCitationAnnotationsIsHarvested() {
    // Citations on a model_output text content can arrive attached to step.delta.
    var annotated =
        stepDelta(
            0,
            "{\"type\":\"text\",\"text\":\"World\",\"annotations\":["
                + "{\"type\":\"url_citation\",\"url\":"
                + "\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/abc\","
                + "\"title\":\"wikipedia.org\",\"start_index\":0,\"end_index\":120},"
                + "{\"type\":\"url_citation\",\"url\":"
                + "\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/xyz\","
                + "\"title\":\"forbes.com\",\"start_index\":121,\"end_index\":240}]}");
    var sse =
        MODEL_OUTPUT_START + HELLO_DELTA + annotated + MODEL_OUTPUT_STOP + INTERACTION_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var done = (StreamEvent.Done) events.getLast();
      assertTrue(done.response().hasCitations(), "Expected citations harvested from step.delta");
      assertEquals(2, done.response().citations().size());

      var first = done.response().citations().get(0);
      assertEquals("wikipedia.org", first.title());
      assertTrue(first.sourceId().startsWith("https://vertexaisearch.cloud.google.com"));
      assertEquals(0, first.startIndex());
      assertEquals(120, first.endIndex());

      var second = done.response().citations().get(1);
      assertEquals("forbes.com", second.title());
    }
  }

  @org.junit.jupiter.api.Test
  void textDeltaSkipsNonUrlCitationAnnotations() {
    var annotated =
        stepDelta(
            0,
            "{\"type\":\"text\",\"text\":\"World\",\"annotations\":["
                + "{\"type\":\"other_annotation\",\"url\":\"https://a\",\"title\":\"a\"},"
                + "{\"type\":\"url_citation\",\"url\":\"https://b\",\"title\":\"b.com\"}]}");
    var sse =
        MODEL_OUTPUT_START + HELLO_DELTA + annotated + MODEL_OUTPUT_STOP + INTERACTION_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var done = (StreamEvent.Done) events.getLast();
      assertEquals(1, done.response().citations().size());
      assertEquals("b.com", done.response().citations().getFirst().title());
    }
  }

  @org.junit.jupiter.api.Test
  void annotationOnlyDeltaIsHarvestedWithoutTextDelta() {
    var deltaJson =
        "{\"type\":\"text_annotation\",\"annotations\":["
            + "{\"type\":\"url_citation\",\"url\":\"https://x\",\"title\":\"x\","
            + "\"start_index\":0,\"end_index\":5}]}";
    var sse =
        MODEL_OUTPUT_START
            + HELLO_DELTA
            + stepDelta(0, deltaJson)
            + MODEL_OUTPUT_STOP
            + INTERACTION_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var textDeltas = events.stream().filter(e -> e instanceof StreamEvent.TextDelta).count();
      assertEquals(1, textDeltas, "annotation-only delta must not surface as a TextDelta");
      var done = (StreamEvent.Done) events.getLast();
      assertTrue(done.response().hasCitations());
    }
  }

  @org.junit.jupiter.api.Test
  void groundedSearchCallDeltaDoesNotAbortStream() {
    // Regression: the google_search_call step arrives in the step.delta union slot with
    // `arguments` as a JSON OBJECT. Before the fix this landed in ContentItem.arguments (a bare
    // String) and aborted the whole stream with "Failed to parse stream event", so the trailing
    // model_output text — and its grounding citations — never surfaced.
    var searchCallDelta =
        stepDelta(
            1,
            "{\"type\":\"google_search_call\",\"id\":\"gsc_1\",\"signature\":\"sig==\","
                + "\"arguments\":{\"queries\":[\"helios framework\"]},"
                + "\"search_type\":\"web_search\"}");
    var annotated =
        stepDelta(
            0,
            "{\"type\":\"text\",\"text\":\"World\",\"annotations\":["
                + "{\"type\":\"url_citation\",\"url\":"
                + "\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/abc\","
                + "\"title\":\"wikipedia.org\",\"start_index\":0,\"end_index\":5}]}");
    var sse =
        MODEL_OUTPUT_START
            + searchCallDelta
            + HELLO_DELTA
            + annotated
            + MODEL_OUTPUT_STOP
            + INTERACTION_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertFalse(
          events.stream().anyMatch(e -> e instanceof StreamEvent.Error),
          "grounded search-call delta must not abort the stream");
      var done = (StreamEvent.Done) events.getLast();
      assertEquals("HelloWorld", done.response().content());
      assertTrue(done.response().hasCitations(), "citations must survive the grounded turn");
      assertEquals("wikipedia.org", done.response().citations().getFirst().title());
    }
  }

  @org.junit.jupiter.api.Test
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
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertFalse(events.stream().anyMatch(e -> e instanceof StreamEvent.Error));
      var done = (StreamEvent.Done) events.getLast();
      assertEquals("Hello", done.response().content());
      assertEquals(FinishReason.STOP, done.response().finishReason());
    }
  }

  @org.junit.jupiter.api.Test
  void interactionCompletedWithoutInteractionFieldIsTolerated() {
    var emptyEnvelope = "data: {\"event_type\":\"interaction.completed\"}\n\n";
    try (var iterator = createIterator(emptyEnvelope, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var done = (StreamEvent.Done) events.getFirst();
      assertEquals(FinishReason.STOP, done.response().finishReason());
      assertNull(done.response().usage());
      assertFalse(done.response().metadata().containsKey(GeminiModel.INTERACTION_ID_KEY));
    }
  }

  @org.junit.jupiter.api.Test
  void interactionCompletedWithoutIdOrStatusDoesNotPopulateMetadata() {
    var envelope = "data: {\"event_type\":\"interaction.completed\",\"interaction\":{}}\n\n";
    try (var iterator = createIterator(envelope, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var done = (StreamEvent.Done) events.getFirst();
      assertFalse(done.response().metadata().containsKey(GeminiModel.INTERACTION_ID_KEY));
    }
  }

  @org.junit.jupiter.api.Test
  void usageWithNullTokenFieldsCoercesToZero() {
    var envelope =
        "data: {\"event_type\":\"interaction.completed\","
            + "\"interaction\":{\"id\":\"x\",\"status\":\"completed\",\"usage\":{}}}\n\n";
    try (var iterator = createIterator(envelope, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var done = (StreamEvent.Done) events.getFirst();
      assertNotNull(done.response().usage());
      assertEquals(0, done.response().usage().inputTokens());
      assertEquals(0, done.response().usage().outputTokens());
    }
  }

  // ── implicit prompt caching on Gemini 2.5+ (hv2-bug2 Issue 1 — Gemini peer) ──

  @org.junit.jupiter.api.Test
  void cachedTokensSurfaceThroughResponseUsageInDisjointShape() {
    // Gemini 2.5+ enables implicit prompt caching automatically. The Interactions API reports
    // total_input_tokens as the TOTAL (inclusive of cached subset) and total_cached_tokens as
    // the cached portion. The Helios provider must re-project into the disjoint Response.Usage
    // shape so cost accounting at the Pricing layer never bills cached tokens at full input
    // rate. Pre-fix: the bug surfaced as Light-Grid-style $0 cost data on Gemini workloads.
    var envelope =
        "data: {\"event_type\":\"interaction.completed\","
            + "\"interaction\":{\"id\":\"x\",\"status\":\"completed\","
            + "\"usage\":{\"total_input_tokens\":2006,\"total_output_tokens\":150,"
            + "\"total_tokens\":2156,\"total_cached_tokens\":1920}}}\n\n";
    try (var iterator = createIterator(envelope, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var done = (StreamEvent.Done) events.getFirst();
      var usage = done.response().usage();
      assertNotNull(usage);
      assertEquals(
          2006 - 1920,
          usage.inputTokens(),
          "Helios inputTokens must be UNCACHED only — wire total_input_tokens minus cached");
      assertEquals(150, usage.outputTokens());
      assertEquals(
          0,
          usage.cacheCreationInputTokens(),
          "Gemini does not premium implicit cache writes — cacheCreation stays 0");
      assertEquals(
          1920,
          usage.cacheReadInputTokens(),
          "total_cached_tokens surfaces as cacheReadInputTokens");
      assertEquals(
          (2006 - 1920) + 150 + 0 + 1920,
          usage.totalTokens(),
          "totalTokens sums every billable token across all four classes");
    }
  }

  @org.junit.jupiter.api.Test
  void cachedTokensZeroProducesUsageWithDisjointSplit() {
    // Gemini 2.5+ emits the field even when no cache hit occurred (cached=0). Verify the
    // re-projection leaves inputTokens at the full wire value with cache fields at zero.
    var envelope =
        "data: {\"event_type\":\"interaction.completed\","
            + "\"interaction\":{\"id\":\"x\",\"status\":\"completed\","
            + "\"usage\":{\"total_input_tokens\":50,\"total_output_tokens\":15,"
            + "\"total_tokens\":65,\"total_cached_tokens\":0}}}\n\n";
    try (var iterator = createIterator(envelope, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var done = (StreamEvent.Done) events.getFirst();
      var usage = done.response().usage();
      assertEquals(50, usage.inputTokens());
      assertEquals(15, usage.outputTokens());
      assertEquals(0, usage.cacheReadInputTokens());
      assertEquals(0, usage.cacheCreationInputTokens());
      assertEquals(65, usage.totalTokens());
    }
  }

  @org.junit.jupiter.api.Test
  void absentCachedTokensFieldTreatsCachedAsZeroForPre25Models() {
    // Gemini 1.5 / 2.0 responses (and OpenAI-compatible proxies) omit total_cached_tokens.
    // The cachedTokensOrZero() helper normalizes to 0 — no NPE, no synthetic cache attribution.
    var envelope =
        "data: {\"event_type\":\"interaction.completed\","
            + "\"interaction\":{\"id\":\"x\",\"status\":\"completed\","
            + "\"usage\":{\"total_input_tokens\":25,\"total_output_tokens\":15,"
            + "\"total_tokens\":40}}}\n\n";
    try (var iterator = createIterator(envelope, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var done = (StreamEvent.Done) events.getFirst();
      var usage = done.response().usage();
      assertEquals(25, usage.inputTokens());
      assertEquals(15, usage.outputTokens());
      assertEquals(0, usage.cacheReadInputTokens());
    }
  }

  @org.junit.jupiter.api.Test
  void cachedTokensExceedingInputTokensDoesNotProduceNegativeUncached() {
    // Defensive: a Gemini server-side accounting bug reporting cached > total_input would
    // produce a negative uncached count under naive arithmetic. The provider clamps at zero.
    var envelope =
        "data: {\"event_type\":\"interaction.completed\","
            + "\"interaction\":{\"id\":\"x\",\"status\":\"completed\","
            + "\"usage\":{\"total_input_tokens\":100,\"total_output_tokens\":20,"
            + "\"total_tokens\":120,\"total_cached_tokens\":500}}}\n\n";
    try (var iterator = createIterator(envelope, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var done = (StreamEvent.Done) events.getFirst();
      var usage = done.response().usage();
      assertEquals(0, usage.inputTokens(), "clamped to zero — never negative");
      assertEquals(500, usage.cacheReadInputTokens());
    }
  }

  @org.junit.jupiter.api.Test
  void usageWithOnlyCachedTokensReportedStillSurfacesUsage() {
    // Pure cache-hit edge case: total_input_tokens=0 but cached>0 (would indicate the server
    // attributed everything to cache). The Usage object must still be populated so cost
    // tracking accounts for the cache-read cost.
    var envelope =
        "data: {\"event_type\":\"interaction.completed\","
            + "\"interaction\":{\"id\":\"x\",\"status\":\"completed\","
            + "\"usage\":{\"total_input_tokens\":0,\"total_output_tokens\":0,"
            + "\"total_tokens\":1234,\"total_cached_tokens\":1234}}}\n\n";
    try (var iterator = createIterator(envelope, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var done = (StreamEvent.Done) events.getFirst();
      assertNotNull(done.response().usage());
      assertEquals(1234, done.response().usage().cacheReadInputTokens());
    }
  }

  @org.junit.jupiter.api.Test
  void thoughtWithEmptySignatureIsIgnored() {
    var sse =
        stepStart(0, "{\"type\":\"thought\",\"signature\":\"\"}")
            + stepStop(0)
            + MODEL_OUTPUT_START
            + HELLO_DELTA
            + MODEL_OUTPUT_STOP
            + INTERACTION_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var done = (StreamEvent.Done) events.getLast();
      assertFalse(done.response().metadata().containsKey(GeminiModel.THOUGHT_SIGNATURES_KEY));
    }
  }

  @org.junit.jupiter.api.Test
  void thoughtSummaryItemsWithEmptyOrNonTextSkipped() {
    var summary =
        "[{\"type\":\"text\",\"text\":\"\"},"
            + "{\"type\":\"image\",\"mime_type\":\"image/png\",\"data\":\"x\"},"
            + "{\"type\":\"text\",\"text\":\"keep\"}]";
    var thought = "{\"type\":\"thought\",\"signature\":\"sig\",\"summary\":" + summary + "}";
    var sse =
        stepStart(0, thought)
            + stepStop(0)
            + MODEL_OUTPUT_START
            + HELLO_DELTA
            + MODEL_OUTPUT_STOP
            + INTERACTION_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var done = (StreamEvent.Done) events.getLast();
      assertEquals("keep", done.response().thinking());
    }
  }

  @org.junit.jupiter.api.Test
  void multipleThoughtSummaryItemsConcatenateThinking() {
    var summary =
        "[{\"type\":\"text\",\"text\":\"first\"},{\"type\":\"text\",\"text\":\"second\"}]";
    var thought = "{\"type\":\"thought\",\"signature\":\"sig\",\"summary\":" + summary + "}";
    var sse =
        stepStart(0, thought)
            + stepStop(0)
            + MODEL_OUTPUT_START
            + HELLO_DELTA
            + MODEL_OUTPUT_STOP
            + INTERACTION_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var done = (StreamEvent.Done) events.getLast();
      assertEquals("first\nsecond", done.response().thinking());
    }
  }

  @org.junit.jupiter.api.Test
  void argumentsDeltaParsingNullPayloadFallsBackToStartArgs() {
    var sse =
        stepStart(
                1,
                "{\"type\":\"function_call\",\"id\":\"call_1\",\"name\":\"get_weather\","
                    + "\"arguments\":{\"city\":\"NYC\"}}")
            + stepArgumentsDelta(1, "null")
            + stepStop(1)
            + INTERACTION_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var tc = ((StreamEvent.ToolCallComplete) events.get(0)).toolCall();
      assertEquals(Map.of("city", "NYC"), tc.arguments());
    }
  }

  @org.junit.jupiter.api.Test
  void textDeltaWithMissingTextFieldIsSilent() {
    var noText = stepDelta(0, "{\"type\":\"text\"}");
    var sse = MODEL_OUTPUT_START + noText + MODEL_OUTPUT_STOP + INTERACTION_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(1, events.size());
      assertInstanceOf(StreamEvent.Done.class, events.getFirst());
      assertEquals("", ((StreamEvent.Done) events.getFirst()).response().content());
    }
  }

  @org.junit.jupiter.api.Test
  void nextWithoutHasNextStillReadsAhead() {
    // Exercises StreamingIterator.next() when nextEvent is not pre-buffered by hasNext().
    try (var iterator = createIterator(TEXT_FLOW, Duration.ofSeconds(5))) {
      var first = iterator.next();
      assertInstanceOf(StreamEvent.TextDelta.class, first);
      var second = iterator.next();
      assertInstanceOf(StreamEvent.Done.class, second);
      assertFalse(iterator.hasNext());
    }
  }

  @org.junit.jupiter.api.Test
  void textDeltaForUnknownStepIndexFallsThroughToContent() {
    // step.delta without a matching step.start — state==null. Text still accumulates onto
    // contentBuilder rather than being silently dropped.
    var sse = HELLO_DELTA + MODEL_OUTPUT_STOP + INTERACTION_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(2, events.size());
      assertInstanceOf(StreamEvent.TextDelta.class, events.get(0));
      assertEquals("Hello", ((StreamEvent.Done) events.get(1)).response().content());
    }
  }

  @org.junit.jupiter.api.Test
  void thoughtWithNoSignatureFieldStillCapturesSummary() {
    var thought = "{\"type\":\"thought\",\"summary\":[{\"type\":\"text\",\"text\":\"inner\"}]}";
    var sse =
        stepStart(0, thought)
            + stepStop(0)
            + MODEL_OUTPUT_START
            + HELLO_DELTA
            + MODEL_OUTPUT_STOP
            + INTERACTION_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var done = (StreamEvent.Done) events.getLast();
      assertEquals("inner", done.response().thinking());
      assertFalse(done.response().metadata().containsKey(GeminiModel.THOUGHT_SIGNATURES_KEY));
    }
  }

  @org.junit.jupiter.api.Test
  void thoughtSummaryItemWithNullTextIsSkipped() {
    var summary = "[{\"type\":\"text\"},{\"type\":\"text\",\"text\":\"only\"}]";
    var thought = "{\"type\":\"thought\",\"signature\":\"sig\",\"summary\":" + summary + "}";
    var sse =
        stepStart(0, thought)
            + stepStop(0)
            + MODEL_OUTPUT_START
            + HELLO_DELTA
            + MODEL_OUTPUT_STOP
            + INTERACTION_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var done = (StreamEvent.Done) events.getLast();
      assertEquals("only", done.response().thinking());
    }
  }

  @org.junit.jupiter.api.Test
  void hasNextIsIdempotentAcrossRepeatedCalls() {
    try (var iterator = createIterator(TEXT_FLOW, Duration.ofSeconds(5))) {
      assertTrue(iterator.hasNext());
      // Second call must short-circuit through the already-buffered nextEvent path.
      assertTrue(iterator.hasNext());
      iterator.next();
      iterator.next();
      // After Done: hasNext must return false even when called again.
      assertFalse(iterator.hasNext());
      assertFalse(iterator.hasNext());
    }
  }

  @org.junit.jupiter.api.Test
  void closeSwallowsIoExceptionFromUnderlyingStream() {
    var failingClose =
        new InputStream() {
          @Override
          public int read() {
            return -1;
          }

          @Override
          public void close() throws java.io.IOException {
            throw new java.io.IOException("close failure");
          }
        };
    var iterator =
        new GeminiModel.StreamingIterator(
            fakeResponse(failingClose), objectMapper, Duration.ofSeconds(5));
    // Drain so the iterator reaches Done and then closes itself.
    while (iterator.hasNext()) {
      iterator.next();
    }
    iterator.close();
    assertFalse(iterator.hasNext());
  }

  @org.junit.jupiter.api.Test
  void incompleteInteractionSetsLengthFinishReason() {
    var incomplete =
        "data: {\"event_type\":\"interaction.completed\","
            + "\"interaction\":{\"id\":\"int_xyz\",\"status\":\"incomplete\","
            + "\"usage\":{\"total_input_tokens\":10,\"total_output_tokens\":5,"
            + "\"total_tokens\":15}}}\n\n";
    try (var iterator = createIterator(incomplete, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(1, events.size());
      var done = (StreamEvent.Done) events.getFirst();
      assertEquals(FinishReason.LENGTH, done.response().finishReason());
    }
  }

  @org.junit.jupiter.api.Test
  void budgetExceededInteractionSetsErrorFinishReason() {
    var exceeded =
        "data: {\"event_type\":\"interaction.completed\","
            + "\"interaction\":{\"id\":\"int_xyz\",\"status\":\"budget_exceeded\","
            + "\"usage\":{\"total_input_tokens\":10,\"total_output_tokens\":5,"
            + "\"total_tokens\":15}}}\n\n";
    try (var iterator = createIterator(exceeded, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(1, events.size());
      var done = (StreamEvent.Done) events.getFirst();
      assertEquals(FinishReason.ERROR, done.response().finishReason());
    }
  }

  @org.junit.jupiter.api.Test
  void sseErrorEventSurfacesAsStreamErrorWithStatusCode() {
    var errorEvent =
        "data: {\"event_type\":\"error\","
            + "\"error\":{\"code\":500,\"message\":\"Internal server error\"}}\n\n";
    try (var iterator = createIterator(errorEvent, Duration.ofSeconds(5))) {
      assertTrue(iterator.hasNext());
      var event = iterator.next();
      assertInstanceOf(StreamEvent.Error.class, event);
      var error = (StreamEvent.Error) event;
      assertTrue(error.message().contains("Internal server error"));
      assertInstanceOf(GeminiException.class, error.cause());
      var ge = (GeminiException) error.cause();
      assertEquals(500, ge.statusCode());
      assertTrue(ge.isRetryable(), "5xx errors must be retryable");
      assertFalse(iterator.hasNext());
    }
  }

  @org.junit.jupiter.api.Test
  void sseErrorEvent429IsRetryable() {
    var errorEvent =
        "data: {\"event_type\":\"error\","
            + "\"error\":{\"code\":429,\"message\":\"Rate limit exceeded\"}}\n\n";
    try (var iterator = createIterator(errorEvent, Duration.ofSeconds(5))) {
      var event = iterator.next();
      assertInstanceOf(StreamEvent.Error.class, event);
      var ge = (GeminiException) ((StreamEvent.Error) event).cause();
      assertEquals(429, ge.statusCode());
      assertTrue(ge.isRetryable());
    }
  }

  @org.junit.jupiter.api.Test
  void sseErrorEvent400IsNotRetryable() {
    var errorEvent =
        "data: {\"event_type\":\"error\","
            + "\"error\":{\"code\":400,\"message\":\"Bad request\"}}\n\n";
    try (var iterator = createIterator(errorEvent, Duration.ofSeconds(5))) {
      var event = iterator.next();
      assertInstanceOf(StreamEvent.Error.class, event);
      var ge = (GeminiException) ((StreamEvent.Error) event).cause();
      assertEquals(400, ge.statusCode());
      assertFalse(ge.isRetryable(), "4xx client errors must not be retryable");
    }
  }

  @org.junit.jupiter.api.Test
  void sseErrorEventWithoutPayloadSurfacesGenericMessage() {
    var errorEvent = "data: {\"event_type\":\"error\"}\n\n";
    try (var iterator = createIterator(errorEvent, Duration.ofSeconds(5))) {
      assertTrue(iterator.hasNext());
      var event = iterator.next();
      assertInstanceOf(StreamEvent.Error.class, event);
      var error = (StreamEvent.Error) event;
      assertEquals("API error", error.message());
      var ge = (GeminiException) error.cause();
      assertEquals(0, ge.statusCode());
    }
  }

  @org.junit.jupiter.api.Test
  void statusUpdateEventCapturesInteractionId() {
    var statusUpdate =
        "data: {\"event_type\":\"interaction.status_update\","
            + "\"interaction_id\":\"int_status\",\"status\":\"requires_action\"}\n\n";
    var sse = INTERACTION_CREATED + statusUpdate;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var done = (StreamEvent.Done) events.getFirst();
      assertEquals(
          "int_status",
          done.response().metadata().get(GeminiModel.INTERACTION_ID_KEY),
          "status_update must capture interaction_id");
    }
  }

  @org.junit.jupiter.api.Test
  void statusUpdateFailedSetsErrorFinishReason() {
    var statusUpdate =
        "data: {\"event_type\":\"interaction.status_update\","
            + "\"interaction_id\":\"int_f\",\"status\":\"failed\"}\n\n";
    try (var iterator = createIterator(statusUpdate, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var done = (StreamEvent.Done) events.getFirst();
      assertEquals(FinishReason.ERROR, done.response().finishReason());
    }
  }

  @org.junit.jupiter.api.Test
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
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var done = (StreamEvent.Done) events.getLast();
      assertEquals("Hello", done.response().content());
      assertFalse(done.response().hasCitations());
    }
  }

  @org.junit.jupiter.api.Test
  void ioExceptionFromReaderEmitsErrorEvent() {
    var failingStream =
        new InputStream() {
          @Override
          public int read() throws java.io.IOException {
            throw new java.io.IOException("Simulated I/O failure");
          }
        };
    try (var iterator =
        new GeminiModel.StreamingIterator(
            fakeResponse(failingStream), objectMapper, Duration.ofSeconds(5))) {
      assertTrue(iterator.hasNext());
      var event = iterator.next();
      assertInstanceOf(StreamEvent.Error.class, event);
      var error = (StreamEvent.Error) event;
      assertTrue(error.message().contains("Stream read error"));
    }
  }

  @org.junit.jupiter.api.Test
  void runtimeExceptionFromReaderEmitsErrorEvent() {
    var failingStream =
        new InputStream() {
          @Override
          public int read() {
            throw new RuntimeException("Unexpected failure");
          }
        };
    try (var iterator =
        new GeminiModel.StreamingIterator(
            fakeResponse(failingStream), objectMapper, Duration.ofSeconds(5))) {
      assertTrue(iterator.hasNext());
      var event = iterator.next();
      assertInstanceOf(StreamEvent.Error.class, event);
      var error = (StreamEvent.Error) event;
      assertTrue(error.message().contains("Stream read error"));
    }
  }

  @org.junit.jupiter.api.Test
  void interruptedThreadEmitsErrorEvent() throws Exception {
    var pipedIn = new PipedInputStream();
    var pipedOut = new PipedOutputStream(pipedIn);
    var events = new java.util.ArrayList<StreamEvent>();
    var thread =
        new Thread(
            () -> {
              try (var iterator =
                  new GeminiModel.StreamingIterator(
                      fakeResponse(pipedIn), objectMapper, Duration.ofSeconds(30))) {
                while (iterator.hasNext()) {
                  events.add(iterator.next());
                }
              }
            });
    thread.start();
    Thread.sleep(100);
    thread.interrupt();
    thread.join(5000);
    assertFalse(events.isEmpty());
    assertInstanceOf(StreamEvent.Error.class, events.getFirst());
    pipedOut.close();
  }

  private GeminiModel.StreamingIterator createIterator(String sseData, Duration idleTimeout) {
    var inputStream = new ByteArrayInputStream(sseData.getBytes(StandardCharsets.UTF_8));
    return new GeminiModel.StreamingIterator(fakeResponse(inputStream), objectMapper, idleTimeout);
  }

  private static HttpResponse<InputStream> fakeResponse(InputStream body) {
    return new HttpResponse<>() {
      @Override
      public int statusCode() {
        return 200;
      }

      @Override
      public HttpHeaders headers() {
        return HttpHeaders.of(Map.of(), (a, b) -> true);
      }

      @Override
      public InputStream body() {
        return body;
      }

      @Override
      public Optional<HttpResponse<InputStream>> previousResponse() {
        return Optional.empty();
      }

      @Override
      public HttpRequest request() {
        return null;
      }

      @Override
      public URI uri() {
        return URI.create("https://test");
      }

      @Override
      public HttpClient.Version version() {
        return HttpClient.Version.HTTP_2;
      }

      @Override
      public Optional<SSLSession> sslSession() {
        return Optional.empty();
      }
    };
  }
}
