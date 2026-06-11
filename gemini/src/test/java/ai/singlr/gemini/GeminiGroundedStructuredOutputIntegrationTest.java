/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.Message;
import ai.singlr.core.model.ModelConfig;
import ai.singlr.core.model.Response;
import ai.singlr.core.schema.OutputSchema;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Integration tests for Google Search grounding against the real Gemini Interactions API, covering
 * both structured output and prose.
 *
 * <p>Closes the coverage gap behind the bug fixed in 2.6.4: a grounded turn emits a {@code
 * google_search_call} step whose {@code arguments} ship as a JSON object on the {@code step.delta}
 * surface. The streaming delta carrier {@code ContentItem.arguments} was a bare {@code String}, so
 * the object aborted the whole stream with {@code "Failed to parse stream event"} before any
 * structured output could surface. No test combined {@code googleSearch(true)} with {@code
 * OutputSchema}, which is why it shipped broken.
 *
 * <p><strong>Citation behaviour, confirmed against the live wire.</strong> Gemini attaches {@code
 * url_citation} annotations to natural-language prose spans (with character offsets). A grounded
 * <em>structured-output</em> turn returns pure JSON with no prose to annotate, so it surfaces no
 * citations even though the search executed — the empty {@link Response#citations()} there is
 * correct API behaviour, not a defect. Grounded <em>prose</em> does surface citations, which {@link
 * #groundedProseSurfacesCitations()} verifies through the same {@code streamAndDrain} path that
 * {@code EnrichmentAgent} reads via {@link Response#citations()}.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class GeminiGroundedStructuredOutputIntegrationTest {

  private static GeminiModel model;

  /**
   * Structured result the model must return after grounding its answer in a web search.
   *
   * @param capital the capital city
   * @param country the country the capital belongs to
   */
  public record CapitalFact(String capital, String country) {}

  @BeforeAll
  static void setUp() {
    var apiKey = System.getenv("GEMINI_API_KEY");
    var config = ModelConfig.newBuilder().withApiKey(apiKey).withGoogleSearch(true).build();
    model = new GeminiModel(GeminiModelId.GEMINI_3_5_FLASH, config);
  }

  @AfterAll
  static void tearDown() {
    if (model != null) {
      model.close();
    }
  }

  @Test
  void groundedStructuredOutputParsesWithoutCrashing() {
    var messages =
        List.of(
            Message.system(
                "Use Google Search to ground your answer, then return ONLY the structured result."),
            Message.user(
                "What is the capital of Australia? Search the web to confirm, then answer."));

    // The deliverable: structured output parsed off a grounded turn. Before the fix the stream
    // died on the google_search_call delta and this call threw GeminiException instead of parsing.
    Response<CapitalFact> response =
        model.chat(messages, List.of(), OutputSchema.of(CapitalFact.class));

    assertNotNull(response.parsed(), "grounded structured output must parse");
    assertTrue(
        response.parsed().capital().toLowerCase().contains("canberra"),
        "grounded answer must be correct — got: " + response.parsed());
  }

  @Test
  void groundedProseSurfacesCitations() {
    // Grounded prose is where citations live. This exercises the same streamAndDrain harvest path
    // EnrichmentAgent depends on, and would also have caught the crash (the search-call delta is
    // emitted regardless of output format).
    var messages =
        List.of(
            Message.system("Answer using Google Search and cite your sources inline."),
            Message.user(
                "What is the capital of Australia and its approximate population? "
                    + "Use Google Search and cite sources."));

    Response<Void> response = model.chat(messages, List.of());

    assertFalse(response.content().isBlank(), "grounded prose turn must produce text");
    assertFalse(
        response.citations().isEmpty(),
        "grounded prose must surface at least one url_citation — got none");
    response
        .citations()
        .forEach(
            c ->
                assertNotNull(
                    c.sourceId(), "every grounding citation must carry a sourceId for enrichment"));
  }
}
