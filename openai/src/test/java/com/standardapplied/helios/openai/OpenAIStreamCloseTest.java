/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.openai;

import static com.standardapplied.helios.core.test.SseEvents.data;

import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.test.StreamCloseContract;
import java.net.URI;
import java.time.Duration;

class OpenAIStreamCloseTest extends StreamCloseContract {

  @Override
  protected Model model(URI server, Duration streamIdleTimeout) {
    return OpenAIFixture.createModel(
        OpenAIModelId.GPT_4O,
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withBaseUrl(server + "/v1/responses")
            .withResponseTimeout(Duration.ofMinutes(10))
            .withStreamIdleTimeout(streamIdleTimeout)
            .build());
  }

  @Override
  protected String hello() {
    return data("{\"type\":\"response.output_text.delta\",\"output_index\":0,\"delta\":\"Hello\"}");
  }

  @Override
  protected String rest() {
    return data(
        "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\","
            + "\"status\":\"completed\",\"usage\":{\"input_tokens\":3,\"output_tokens\":1}}}");
  }

  @Override
  protected String apiName() {
    return "OpenAI API";
  }

  @Override
  protected String providerName() {
    return OpenAIProvider.PROVIDER_NAME;
  }
}
