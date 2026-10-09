/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.gemini;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.test.Accepted;
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
 * structured output could surface. No test combined {@code withWebSearch(true)} with {@code
 * OutputSchema}, which is why it shipped broken.
 *
 * <p>Grounded structured output is constrained by {@code response_format}, so it always parses.
 * Whether the model cites a source is its choice: the prose case skips when it cites none, and
 * {@code GeminiStreamTranscriptTest "grounded"}, a recorded grounded turn, proves Helios harvests
 * the {@code url_citation} annotations into {@link Response#citations()}.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class GeminiGroundedStructuredOutputIntegrationTest {

  private static Model model;

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
    var config = ModelConfig.newBuilder().withApiKey(apiKey).withWebSearch(true).build();
    model = new GeminiProvider().create(GeminiModelId.GEMINI_3_5_FLASH.id(), config);
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
  }

  @Test
  void groundedProseSurfacesCitations() {
    var messages =
        List.of(
            Message.system("Answer using Google Search and cite your sources inline."),
            Message.user(
                "What is the capital of Australia and its approximate population? "
                    + "Use Google Search and cite sources."));

    Response<Void> response = model.chat(messages, List.of());

    Accepted.textReply(response);
    assumeTrue(
        response.hasCitations(),
        "gemini-3.5-flash cited no source; GeminiStreamTranscriptTest \"grounded\" covers the"
            + " citation harvest");
    response
        .citations()
        .forEach(
            c ->
                assertNotNull(
                    c.sourceId(), "every grounding citation must carry a sourceId for enrichment"));
  }
}
