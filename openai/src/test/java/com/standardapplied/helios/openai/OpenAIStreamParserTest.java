/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.TransientStreamException;
import com.standardapplied.helios.core.provider.SseReader;
import com.standardapplied.helios.core.test.ExchangeTurns;
import com.standardapplied.helios.core.test.SseEvents;
import com.standardapplied.helios.openai.api.ResponsesRequest;
import java.io.EOFException;
import org.junit.jupiter.api.Test;

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
