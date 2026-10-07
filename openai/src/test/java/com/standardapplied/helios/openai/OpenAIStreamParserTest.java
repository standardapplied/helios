/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.provider.ChatExchange;
import com.standardapplied.helios.core.provider.SseReader;
import com.standardapplied.helios.openai.api.ResponsesRequest;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

class OpenAIStreamParserTest {

  private static Response<Void> drain(SseReader events) {
    return new ChatExchange<ResponsesRequest>(
            OpenAIProvider.PROVIDER_NAME, "OpenAI API", request -> events, OpenAIException::new)
        .chat(null);
  }

  @Test
  void drainToResponseExtractsResponse() {
    var sse =
        "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Hi\"}\n\n"
            + "data: {\"type\":\"response.completed\",\"response\":{"
            + "\"id\":\"resp_1\",\"status\":\"completed\","
            + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"total_tokens\":15}}}\n\n";

    var objectMapper =
        JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    try (var iterator =
        new SseReader(
            new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)),
            Duration.ofSeconds(5),
            new OpenAIStreamParser(),
            OpenAIException::new)) {
      var response = drain(iterator);
      assertEquals("Hi", response.content());
      assertEquals(FinishReason.STOP, response.finishReason());
      assertNotNull(response.usage());
    }
  }

  @Test
  void drainToResponseRethrowsOpenAIException() {
    var sse =
        "data: {\"type\":\"response.failed\",\"response\":{"
            + "\"id\":\"resp_1\",\"status\":\"failed\"}}\n\n";

    var objectMapper =
        JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    try (var iterator =
        new SseReader(
            new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)),
            Duration.ofSeconds(5),
            new OpenAIStreamParser(),
            OpenAIException::new)) {
      assertThrows(OpenAIException.class, () -> drain(iterator));
    }
  }

  @Test
  void drainToResponseWrapsGenericError() {
    var sse = "data: {\"type\":\"error\",\"message\":\"bad things\"}\n\n";

    var objectMapper =
        JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    try (var iterator =
        new SseReader(
            new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)),
            Duration.ofSeconds(5),
            new OpenAIStreamParser(),
            OpenAIException::new)) {
      assertThrows(OpenAIException.class, () -> drain(iterator));
    }
  }

  @Test
  void drainToResponseEmptyStreamReturnsDone() {
    var sse = "";

    var objectMapper =
        JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    try (var iterator =
        new SseReader(
            new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)),
            Duration.ofSeconds(5),
            new OpenAIStreamParser(),
            OpenAIException::new)) {
      var response = drain(iterator);
      assertNotNull(response);
      assertEquals("", response.content());
      assertEquals(FinishReason.STOP, response.finishReason());
    }
  }
}
