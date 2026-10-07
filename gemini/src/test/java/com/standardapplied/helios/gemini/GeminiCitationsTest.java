/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import static com.standardapplied.helios.gemini.GeminiSse.HELLO_DELTA;
import static com.standardapplied.helios.gemini.GeminiSse.INTERACTION_COMPLETED;
import static com.standardapplied.helios.gemini.GeminiSse.MODEL_OUTPUT_START;
import static com.standardapplied.helios.gemini.GeminiSse.MODEL_OUTPUT_STOP;
import static com.standardapplied.helios.gemini.GeminiSse.drain;
import static com.standardapplied.helios.gemini.GeminiSse.stepDelta;
import static com.standardapplied.helios.gemini.GeminiSse.stepStart;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.StreamEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The citations a Gemini stream carries: the url_citation annotations of its model output. */
class GeminiCitationsTest {

  @Test
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
    var events = drain(sse);
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

  @Test
  void textDeltaSkipsNonUrlCitationAnnotations() {
    var annotated =
        stepDelta(
            0,
            "{\"type\":\"text\",\"text\":\"World\",\"annotations\":["
                + "{\"type\":\"other_annotation\",\"url\":\"https://a\",\"title\":\"a\"},"
                + "{\"type\":\"url_citation\",\"url\":\"https://b\",\"title\":\"b.com\"}]}");
    var sse =
        MODEL_OUTPUT_START + HELLO_DELTA + annotated + MODEL_OUTPUT_STOP + INTERACTION_COMPLETED;
    var events = drain(sse);
    var done = (StreamEvent.Done) events.getLast();
    assertEquals(1, done.response().citations().size());
    assertEquals("b.com", done.response().citations().getFirst().title());
  }

  @Test
  void onlyModelOutputStepsContributeCitationsInArrivalOrder() {
    var sse =
        stepStart(0, "{\"type\":\"user_input\",\"content\":[" + citedText("u", "https://u") + "]}")
            + stepStart(
                1, "{\"type\":\"thought\",\"summary\":[" + citedText("t", "https://t") + "]}")
            + stepStart(2, "{\"type\":\"model_output\",\"content\":[]}")
            + stepStart(
                3, "{\"type\":\"model_output\",\"content\":[" + citedText("A", "https://a") + "]}")
            + stepStart(
                4, "{\"type\":\"model_output\",\"content\":[" + citedText("B", "https://b") + "]}")
            + INTERACTION_COMPLETED;
    var events = drain(sse);
    var done = (StreamEvent.Done) events.getLast();
    assertEquals(
        List.of("https://a", "https://b"),
        done.response().citations().stream().map(citation -> citation.sourceId()).toList());
  }

  @Test
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
    var events = drain(sse);
    var textDeltas = events.stream().filter(e -> e instanceof StreamEvent.TextDelta).count();
    assertEquals(1, textDeltas, "annotation-only delta must not surface as a TextDelta");
    var done = (StreamEvent.Done) events.getLast();
    assertTrue(done.response().hasCitations());
  }

  @Test
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
    var events = drain(sse);
    assertFalse(
        events.stream().anyMatch(e -> e instanceof StreamEvent.Error),
        "grounded search-call delta must not abort the stream");
    var done = (StreamEvent.Done) events.getLast();
    assertEquals("HelloWorld", done.response().content());
    assertTrue(done.response().hasCitations(), "citations must survive the grounded turn");
    assertEquals("wikipedia.org", done.response().citations().getFirst().title());
  }

  @Test
  void groundedStructuredOutputHarvestsTextAnnotationDeltaCitations() {
    // Live-wire shape (Api-Revision 2026-05-20): on a grounded *structured-output* turn the
    // model_output text is JSON, and grounding citations arrive as a SEPARATE delta with
    // type "text_annotation_delta" (annotations, no text). The type-agnostic harvest branch must
    // fold them into Response.citations() — i.e. structured mode is NOT citation-free, contrary to
    // a naive trivial-query probe.
    var jsonText =
        stepDelta(0, "{\"type\":\"text\",\"text\":\"{\\\"answer\\\":\\\"Canberra\\\"}\"}");
    var annotationDelta =
        stepDelta(
            0,
            "{\"type\":\"text_annotation_delta\",\"annotations\":["
                + "{\"type\":\"url_citation\",\"url\":"
                + "\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/abc\","
                + "\"title\":\"wikipedia.org\",\"start_index\":0,\"end_index\":20},"
                + "{\"type\":\"url_citation\",\"url\":"
                + "\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/xyz\","
                + "\"title\":\"britannica.com\",\"start_index\":0,\"end_index\":20}]}");
    var sse =
        MODEL_OUTPUT_START + jsonText + annotationDelta + MODEL_OUTPUT_STOP + INTERACTION_COMPLETED;
    var events = drain(sse);
    assertFalse(events.stream().anyMatch(e -> e instanceof StreamEvent.Error));
    var done = (StreamEvent.Done) events.getLast();
    assertEquals("{\"answer\":\"Canberra\"}", done.response().content());
    assertEquals(
        2,
        done.response().citations().size(),
        "structured-mode text_annotation_delta citations must be harvested");
    assertEquals("wikipedia.org", done.response().citations().getFirst().title());
    // The annotation-only delta must not masquerade as model text.
    var textDeltas = events.stream().filter(e -> e instanceof StreamEvent.TextDelta).count();
    assertEquals(1, textDeltas);
  }

  private static String citedText(String text, String url) {
    return "{\"type\":\"text\",\"text\":\""
        + text
        + "\",\"annotations\":[{\"type\":\"url_citation\",\"url\":\""
        + url
        + "\"}]}";
  }
}
