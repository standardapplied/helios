/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import static com.standardapplied.helios.gemini.GeminiSse.HELLO_DELTA;
import static com.standardapplied.helios.gemini.GeminiSse.INTERACTION_COMPLETED;
import static com.standardapplied.helios.gemini.GeminiSse.INTERACTION_COMPLETED_NO_USAGE;
import static com.standardapplied.helios.gemini.GeminiSse.INTERACTION_CREATED;
import static com.standardapplied.helios.gemini.GeminiSse.INTERACTION_IN_PROGRESS;
import static com.standardapplied.helios.gemini.GeminiSse.MODEL_OUTPUT_START;
import static com.standardapplied.helios.gemini.GeminiSse.MODEL_OUTPUT_STOP;
import static com.standardapplied.helios.gemini.GeminiSse.TEXT_FLOW;
import static com.standardapplied.helios.gemini.GeminiSse.drain;
import static com.standardapplied.helios.gemini.GeminiSse.interactionEnded;
import static com.standardapplied.helios.gemini.GeminiSse.reader;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.provider.SseReader;
import com.standardapplied.helios.core.test.SseEvents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The interaction envelope of a Gemini stream: its id, status, usage and errors, and the
 * informational events around its steps.
 */
class GeminiStreamParserTest {

  @Test
  void usageFromCompleteEvent() {
    var events = drain(TEXT_FLOW);
    var done = (StreamEvent.Done) events.getLast();
    assertNotNull(done.response().usage());
    assertEquals(10, done.response().usage().inputTokens());
    assertEquals(5, done.response().usage().outputTokens());
  }

  @Test
  void usageOmittedDoesNotPopulateResponseUsage() {
    var sse = MODEL_OUTPUT_START + HELLO_DELTA + MODEL_OUTPUT_STOP + INTERACTION_COMPLETED_NO_USAGE;
    var events = drain(sse);
    var done = (StreamEvent.Done) events.getLast();
    assertNull(done.response().usage());
  }

  @Test
  void interactionCreatedAndInProgressAreInformational() {
    var sse =
        INTERACTION_CREATED
            + INTERACTION_IN_PROGRESS
            + MODEL_OUTPUT_START
            + HELLO_DELTA
            + MODEL_OUTPUT_STOP
            + INTERACTION_COMPLETED;
    var events = drain(sse);
    // Only TextDelta + Done bubble out — interaction.* and step.start/stop are silent.
    assertEquals(2, events.size());
    assertInstanceOf(StreamEvent.TextDelta.class, events.get(0));
    assertInstanceOf(StreamEvent.Done.class, events.get(1));
    var done = (StreamEvent.Done) events.get(1);
    assertEquals("int_xyz", done.response().metadata().get(ContinuationPoint.INTERACTION_ID_KEY));
  }

  @Test
  void unknownEventTypeIsTolerated() {
    var unknown = "data: {\"event_type\":\"interaction.unknown\"}\n\n";
    var sse = unknown + TEXT_FLOW;
    var events = drain(sse);
    assertEquals(2, events.size());
    assertInstanceOf(StreamEvent.TextDelta.class, events.get(0));
  }

  @Test
  void emptyStreamProducesDoneWithEmptyContent() {
    var events = drain(INTERACTION_COMPLETED);
    assertEquals(1, events.size());
    var done = (StreamEvent.Done) events.getFirst();
    assertEquals("", done.response().content());
    assertEquals(FinishReason.STOP, done.response().finishReason());
  }

  @Test
  void interactionCompletedWithoutInteractionFieldIsTolerated() {
    var emptyEnvelope = "data: {\"event_type\":\"interaction.completed\"}\n\n";
    var events = drain(emptyEnvelope);
    var done = (StreamEvent.Done) events.getFirst();
    assertEquals(FinishReason.STOP, done.response().finishReason());
    assertNull(done.response().usage());
    assertFalse(done.response().metadata().containsKey(ContinuationPoint.INTERACTION_ID_KEY));
  }

  @Test
  void interactionCompletedWithoutIdOrStatusDoesNotPopulateMetadata() {
    var envelope = "data: {\"event_type\":\"interaction.completed\",\"interaction\":{}}\n\n";
    var events = drain(envelope);
    var done = (StreamEvent.Done) events.getFirst();
    assertFalse(done.response().metadata().containsKey(ContinuationPoint.INTERACTION_ID_KEY));
  }

  @Test
  void usageWithNullTokenFieldsCoercesToZero() {
    var envelope =
        "data: {\"event_type\":\"interaction.completed\","
            + "\"interaction\":{\"id\":\"x\",\"status\":\"completed\",\"usage\":{}}}\n\n";
    var events = drain(envelope);
    var done = (StreamEvent.Done) events.getFirst();
    assertNotNull(done.response().usage());
    assertEquals(0, done.response().usage().inputTokens());
    assertEquals(0, done.response().usage().outputTokens());
  }

  @Test
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
    var events = drain(envelope);
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
        1920, usage.cacheReadInputTokens(), "total_cached_tokens surfaces as cacheReadInputTokens");
    assertEquals(
        (2006 - 1920) + 150 + 0 + 1920,
        usage.totalTokens(),
        "totalTokens sums every billable token across all four classes");
  }

  @Test
  void cachedTokensZeroProducesUsageWithDisjointSplit() {
    // Gemini 2.5+ emits the field even when no cache hit occurred (cached=0). Verify the
    // re-projection leaves inputTokens at the full wire value with cache fields at zero.
    var envelope =
        "data: {\"event_type\":\"interaction.completed\","
            + "\"interaction\":{\"id\":\"x\",\"status\":\"completed\","
            + "\"usage\":{\"total_input_tokens\":50,\"total_output_tokens\":15,"
            + "\"total_tokens\":65,\"total_cached_tokens\":0}}}\n\n";
    var events = drain(envelope);
    var done = (StreamEvent.Done) events.getFirst();
    var usage = done.response().usage();
    assertEquals(50, usage.inputTokens());
    assertEquals(15, usage.outputTokens());
    assertEquals(0, usage.cacheReadInputTokens());
    assertEquals(0, usage.cacheCreationInputTokens());
    assertEquals(65, usage.totalTokens());
  }

  @Test
  void absentCachedTokensFieldTreatsCachedAsZeroForPre25Models() {
    // Gemini 1.5 / 2.0 responses (and OpenAI-compatible proxies) omit total_cached_tokens.
    // The cachedTokensOrZero() helper normalizes to 0 — no NPE, no synthetic cache attribution.
    var envelope =
        "data: {\"event_type\":\"interaction.completed\","
            + "\"interaction\":{\"id\":\"x\",\"status\":\"completed\","
            + "\"usage\":{\"total_input_tokens\":25,\"total_output_tokens\":15,"
            + "\"total_tokens\":40}}}\n\n";
    var events = drain(envelope);
    var done = (StreamEvent.Done) events.getFirst();
    var usage = done.response().usage();
    assertEquals(25, usage.inputTokens());
    assertEquals(15, usage.outputTokens());
    assertEquals(0, usage.cacheReadInputTokens());
  }

  @Test
  void cachedTokensExceedingInputTokensDoesNotProduceNegativeUncached() {
    // Defensive: a Gemini server-side accounting bug reporting cached > total_input would
    // produce a negative uncached count under naive arithmetic. The provider clamps at zero.
    var envelope =
        "data: {\"event_type\":\"interaction.completed\","
            + "\"interaction\":{\"id\":\"x\",\"status\":\"completed\","
            + "\"usage\":{\"total_input_tokens\":100,\"total_output_tokens\":20,"
            + "\"total_tokens\":120,\"total_cached_tokens\":500}}}\n\n";
    var events = drain(envelope);
    var done = (StreamEvent.Done) events.getFirst();
    var usage = done.response().usage();
    assertEquals(0, usage.inputTokens(), "clamped to zero — never negative");
    assertEquals(500, usage.cacheReadInputTokens());
  }

  @Test
  void usageWithOnlyCachedTokensReportedStillSurfacesUsage() {
    // Pure cache-hit edge case: total_input_tokens=0 but cached>0 (would indicate the server
    // attributed everything to cache). The Usage object must still be populated so cost
    // tracking accounts for the cache-read cost.
    var envelope =
        "data: {\"event_type\":\"interaction.completed\","
            + "\"interaction\":{\"id\":\"x\",\"status\":\"completed\","
            + "\"usage\":{\"total_input_tokens\":0,\"total_output_tokens\":0,"
            + "\"total_tokens\":1234,\"total_cached_tokens\":1234}}}\n\n";
    var events = drain(envelope);
    var done = (StreamEvent.Done) events.getFirst();
    assertNotNull(done.response().usage());
    assertEquals(1234, done.response().usage().cacheReadInputTokens());
  }

  @Test
  void sseErrorEventSurfacesAsStreamErrorWithStatusCode() {
    var errorEvent =
        "data: {\"event_type\":\"error\","
            + "\"error\":{\"code\":500,\"message\":\"Internal server error\"}}\n\n";
    try (var iterator = reader(SseEvents.body(errorEvent))) {
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

  @Test
  void sseErrorEvent429IsRetryable() {
    var errorEvent =
        "data: {\"event_type\":\"error\","
            + "\"error\":{\"code\":429,\"message\":\"Rate limit exceeded\"}}\n\n";
    try (var iterator = reader(SseEvents.body(errorEvent))) {
      var event = iterator.next();
      assertInstanceOf(StreamEvent.Error.class, event);
      var ge = (GeminiException) ((StreamEvent.Error) event).cause();
      assertEquals(429, ge.statusCode());
      assertTrue(ge.isRetryable());
    }
  }

  @Test
  void sseErrorEvent400IsNotRetryable() {
    var errorEvent =
        "data: {\"event_type\":\"error\","
            + "\"error\":{\"code\":400,\"message\":\"Bad request\"}}\n\n";
    try (var iterator = reader(SseEvents.body(errorEvent))) {
      var event = iterator.next();
      assertInstanceOf(StreamEvent.Error.class, event);
      var ge = (GeminiException) ((StreamEvent.Error) event).cause();
      assertEquals(400, ge.statusCode());
      assertFalse(ge.isRetryable(), "4xx client errors must not be retryable");
    }
  }

  @Test
  void sseErrorEventWithoutPayloadSurfacesGenericMessage() {
    var errorEvent = "data: {\"event_type\":\"error\"}\n\n";
    try (var iterator = reader(SseEvents.body(errorEvent))) {
      assertTrue(iterator.hasNext());
      var event = iterator.next();
      assertInstanceOf(StreamEvent.Error.class, event);
      var error = (StreamEvent.Error) event;
      assertEquals("API error", error.message());
      var ge = (GeminiException) error.cause();
      assertEquals(0, ge.statusCode());
    }
  }

  @Test
  void statusUpdateEventCapturesInteractionId() {
    var statusUpdate =
        "data: {\"event_type\":\"interaction.status_update\","
            + "\"interaction_id\":\"int_status\",\"status\":\"requires_action\"}\n\n";
    var sse = INTERACTION_CREATED + statusUpdate;
    var events = drain(sse);
    var done = (StreamEvent.Done) events.getFirst();
    assertEquals(
        "int_status",
        done.response().metadata().get(ContinuationPoint.INTERACTION_ID_KEY),
        "status_update must capture interaction_id");
  }

  @Test
  void statusUpdateFailedSetsErrorFinishReason() {
    var statusUpdate =
        "data: {\"event_type\":\"interaction.status_update\","
            + "\"interaction_id\":\"int_f\",\"status\":\"failed\"}\n\n";
    var events = drain(statusUpdate);
    var done = (StreamEvent.Done) events.getFirst();
    assertEquals(FinishReason.ERROR, done.response().finishReason());
  }

  @Test
  void statelessIteratorDoesNotPropagateInteractionIdAndReportsApiVersion() {
    var sse =
        "data: {\"event_type\":\"interaction.status_update\","
            + "\"interaction_id\":\"interaction-secret\",\"status\":\"completed\"}\n\n";
    var events =
        SseEvents.drain(
            new SseReader(
                SseEvents.body(sse),
                SseEvents.NEVER_IDLE,
                new GeminiStreamParser(false, "v1beta"),
                GeminiException::new));
    var done = (StreamEvent.Done) events.getLast();
    assertFalse(done.response().metadata().containsKey(ContinuationPoint.INTERACTION_ID_KEY));
    assertEquals("v1beta", done.response().metadata().get(GeminiResponseAssembler.API_VERSION_KEY));
    assertFalse(done.response().metadata().containsValue("interaction-secret"));
  }

  @ParameterizedTest(name = "{0} interaction sets {1}")
  @CsvSource({"failed, ERROR", "incomplete, LENGTH", "budget_exceeded, ERROR"})
  void finishReasonFollowsTheInteractionStatus(String status, FinishReason finishReason) {
    var events = drain(interactionEnded(status));

    assertEquals(1, events.size());
    assertEquals(finishReason, ((StreamEvent.Done) events.getFirst()).response().finishReason());
  }
}
