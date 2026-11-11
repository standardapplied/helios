/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.model.TransientStreamException;
import com.standardapplied.helios.core.provider.SseReader;
import com.standardapplied.helios.core.test.ExchangeTurns;
import com.standardapplied.helios.core.test.SseEvents;
import com.standardapplied.helios.openai.api.ResponsesRequest;
import java.io.EOFException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class OpenAIStreamParserTest {

  private static Response<Void> drain(String sse) {
    try (var events =
        new SseReader(
            SseEvents.body(sse),
            SseEvents.NEVER_IDLE,
            new OpenAIStreamParser(),
            OpenAIException::new)) {
      return ExchangeTurns.chat(
          (ResponsesRequest request) -> events,
          null,
          OpenAIProvider.PROVIDER_NAME,
          OpenAIException::new);
    }
  }

  @Test
  void drainToResponseExtractsResponse() {
    var sse =
        "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Hi\"}\n\n"
            + "data: {\"type\":\"response.completed\",\"response\":{"
            + "\"id\":\"resp_1\",\"status\":\"completed\","
            + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"total_tokens\":15}}}\n\n";

    var response = drain(sse);

    assertEquals("Hi", response.content());
    assertEquals(FinishReason.STOP, response.finishReason());
    assertNotNull(response.usage());
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {
        ",\"output_tokens_details\":{\"reasoning_tokens\":250} | 250",
        "'' | 0",
        ",\"output_tokens_details\":{} | 0",
        ",\"output_tokens_details\":{\"reasoning_tokens\":400} | 300"
      })
  void theReasoningCountIsThePartOfTheOutputSpentReasoningCutToTheOutput(
      String details, int reasoningTokens) {
    var sse =
        "data: {\"type\":\"response.completed\",\"response\":{"
            + "\"id\":\"resp_1\",\"status\":\"completed\","
            + "\"usage\":{\"input_tokens\":10,\"output_tokens\":300,\"total_tokens\":310"
            + details
            + "}}}\n\n";

    var usage = drain(sse).usage();

    assertEquals(300, usage.outputTokens());
    assertEquals(reasoningTokens, usage.reasoningTokens());
    assertEquals(310, usage.totalTokens());
  }

  @Test
  void theEventsBeforeTheTerminalOneYieldNoUsage() {
    var parser = new OpenAIStreamParser();
    var withUsage =
        "{\"id\":\"resp_1\",\"status\":\"in_progress\","
            + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"total_tokens\":15}}";

    assertNull(parser.parse("{\"type\":\"response.created\",\"response\":" + withUsage + "}"));
    assertNull(parser.parse("{\"type\":\"response.in_progress\",\"response\":" + withUsage + "}"));
    assertNull(parser.partial().usage());
  }

  @Test
  void theModelTheTerminalResponseNamesIsTheModelThatServedIt() {
    var completed =
        "data: {\"type\":\"response.completed\",\"response\":{"
            + "\"status\":\"completed\",\"model\":\"gpt-5.6-2026-08-01\"}}\n\n";
    var failed =
        "{\"type\":\"response.failed\",\"response\":{"
            + "\"status\":\"failed\",\"model\":\"gpt-5.6-2026-08-01\"}}";

    var response = drain(completed);
    var error = assertInstanceOf(StreamEvent.Error.class, new OpenAIStreamParser().parse(failed));

    assertEquals("gpt-5.6-2026-08-01", response.metadata().get(Response.RESPONSE_MODEL_KEY));
    assertEquals("gpt-5.6-2026-08-01", error.partial().metadata().get(Response.RESPONSE_MODEL_KEY));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", ",\"model\":null", ",\"model\":\" \""})
  void aResponseNamingNoModelOrABlankOneReportsNone(String model) {
    var sse =
        "data: {\"type\":\"response.completed\",\"response\":{"
            + "\"status\":\"completed\""
            + model
            + "}}\n\n";

    assertFalse(drain(sse).metadata().containsKey(Response.RESPONSE_MODEL_KEY));
  }

  @Test
  void drainToResponseRethrowsOpenAIException() {
    var sse =
        "data: {\"type\":\"response.failed\",\"response\":{"
            + "\"id\":\"resp_1\",\"status\":\"failed\"}}\n\n";

    assertThrows(OpenAIException.class, () -> drain(sse));
  }

  @Test
  void drainToResponseWrapsGenericError() {
    var sse = "data: {\"type\":\"error\",\"message\":\"bad things\"}\n\n";

    assertThrows(OpenAIException.class, () -> drain(sse));
  }

  @Test
  void drainingAnEmptyStreamFailsAsARetryableTruncation() {
    var thrown = assertThrows(TransientStreamException.class, () -> drain(""));

    assertEquals("Stream ended before its terminal event", thrown.getMessage());
    assertInstanceOf(EOFException.class, thrown.getCause());
  }
}
