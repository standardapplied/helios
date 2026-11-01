/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import static com.standardapplied.helios.gemini.GeminiSse.HELLO_DELTA;
import static com.standardapplied.helios.gemini.GeminiSse.INTERACTION_COMPLETED;
import static com.standardapplied.helios.gemini.GeminiSse.MODEL_OUTPUT_START;
import static com.standardapplied.helios.gemini.GeminiSse.MODEL_OUTPUT_STOP;

import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.test.StreamCloseContract;
import java.net.URI;
import java.time.Duration;

class GeminiStreamCloseTest extends StreamCloseContract {

  @Override
  protected Model model(URI server, Duration streamIdleTimeout) {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withBaseUrl(server.toString())
            .withResponseTimeout(Duration.ofMinutes(10))
            .withStreamIdleTimeout(streamIdleTimeout)
            .build();
    return new GeminiProvider().create(GeminiModelId.GEMINI_3_8_FLASH.id(), config);
  }

  @Override
  protected String hello() {
    return MODEL_OUTPUT_START + HELLO_DELTA;
  }

  @Override
  protected String rest() {
    return MODEL_OUTPUT_STOP + INTERACTION_COMPLETED;
  }

  @Override
  protected String apiName() {
    return "Gemini API";
  }

  @Override
  protected String providerName() {
    return GeminiProvider.PROVIDER_NAME;
  }
}
