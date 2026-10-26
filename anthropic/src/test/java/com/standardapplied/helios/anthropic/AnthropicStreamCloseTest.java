/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import static com.standardapplied.helios.core.test.SseEvents.named;

import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.test.StreamCloseContract;
import java.net.URI;
import java.time.Duration;

class AnthropicStreamCloseTest extends StreamCloseContract {

  @Override
  protected Model model(URI server, Duration streamIdleTimeout) {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withBaseUrl(server.toString())
            .withResponseTimeout(Duration.ofMinutes(10))
            .withStreamIdleTimeout(streamIdleTimeout)
            .build();
    return new AnthropicProvider().create(AnthropicModelId.CLAUDE_SONNET_4_6.id(), config);
  }

  @Override
  protected String hello() {
    return named(
            "message_start",
            "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\","
                + "\"role\":\"assistant\",\"content\":[],\"model\":\"claude-sonnet-4-6\","
                + "\"stop_reason\":null,\"usage\":{\"input_tokens\":3,\"output_tokens\":1}}}")
        + named(
            "content_block_start",
            "{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}")
        + named(
            "content_block_delta",
            "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}");
  }

  @Override
  protected String rest() {
    return named("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}")
        + named(
            "message_delta",
            "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
                + "\"usage\":{\"output_tokens\":2}}")
        + named("message_stop", "{\"type\":\"message_stop\"}");
  }

  @Override
  protected String apiName() {
    return "Anthropic API";
  }

  @Override
  protected String providerName() {
    return AnthropicProvider.PROVIDER_NAME;
  }
}
