/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.anthropic.api.MessagesRequest;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.provider.SseReader;
import com.standardapplied.helios.core.tool.Tool;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;

/**
 * What the Anthropic unit tests share: a model or request builder for a model and configuration,
 * and canned Messages API stream bodies read through the provider's own parser.
 */
final class AnthropicFixture {

  /** A turn that thinks, calls a tool, thinks again and calls a second tool. */
  static final String INTERLEAVED_THINKING_SSE =
      "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":10}}}\n\n"
          + "data: {\"type\":\"content_block_start\",\"index\":0,"
          + "\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}\n\n"
          + "data: {\"type\":\"content_block_delta\",\"index\":0,"
          + "\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"Reading the first profile.\"}}\n\n"
          + "data: {\"type\":\"content_block_delta\",\"index\":0,"
          + "\"delta\":{\"type\":\"signature_delta\",\"signature\":\"SIG-1\"}}\n\n"
          + "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
          + "data: {\"type\":\"content_block_start\",\"index\":1,"
          + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"get_profile\"}}\n\n"
          + "data: {\"type\":\"content_block_delta\",\"index\":1,"
          + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"id\\\":1}\"}}\n\n"
          + "data: {\"type\":\"content_block_stop\",\"index\":1}\n\n"
          + "data: {\"type\":\"content_block_start\",\"index\":2,"
          + "\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}\n\n"
          + "data: {\"type\":\"content_block_delta\",\"index\":2,"
          + "\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"Reading the second profile.\"}}\n\n"
          + "data: {\"type\":\"content_block_delta\",\"index\":2,"
          + "\"delta\":{\"type\":\"signature_delta\",\"signature\":\"SIG-2\"}}\n\n"
          + "data: {\"type\":\"content_block_stop\",\"index\":2}\n\n"
          + "data: {\"type\":\"content_block_start\",\"index\":3,"
          + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_2\",\"name\":\"get_profile\"}}\n\n"
          + "data: {\"type\":\"content_block_delta\",\"index\":3,"
          + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"id\\\":2}\"}}\n\n"
          + "data: {\"type\":\"content_block_stop\",\"index\":3}\n\n"
          + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},"
          + "\"usage\":{\"output_tokens\":40}}\n\n"
          + "data: {\"type\":\"message_stop\"}\n\n";

  private static final AnthropicProvider PROVIDER = new AnthropicProvider();

  private AnthropicFixture() {}

  static Model model(AnthropicModelId model, ModelConfig config) {
    return PROVIDER.create(model.id(), config);
  }

  static Model model(String modelId, ModelConfig config) {
    return PROVIDER.create(modelId, config);
  }

  static Model model(AnthropicModelId model, ModelConfig config, CachePolicy cachePolicy) {
    return PROVIDER.create(model.id(), config, cachePolicy);
  }

  static AnthropicRequestBuilder requests(AnthropicModelId model, ModelConfig config) {
    return requests(model, config, CachePolicy.shortLived());
  }

  static AnthropicRequestBuilder requests(
      AnthropicModelId model, ModelConfig config, CachePolicy cachePolicy) {
    return new AnthropicRequestBuilder(model.id(), model, config, cachePolicy);
  }

  static AnthropicRequestBuilder requests(String wireModelId, ModelConfig config) {
    return new AnthropicRequestBuilder(
        wireModelId, AnthropicModelId.fromWireId(wireModelId), config, CachePolicy.shortLived());
  }

  /**
   * {@code message} as produced under the prompt {@code requests} sends with {@code tools} and no
   * system prompt, so a binding model replays its reasoning.
   */
  static Message underCurrentPrompt(
      Message message, AnthropicRequestBuilder requests, List<Tool> tools) {
    var metadata = new HashMap<>(message.metadata());
    metadata.put(
        ReasoningReplay.PROMPT_STAMP_KEY, requests.build(List.of(), tools, null).promptStamp());
    return Message.newBuilder(message).withMetadata(metadata).build();
  }

  /** The request for a one-message conversation with {@code wireModelId} under {@code config}. */
  static MessagesRequest requestFor(String wireModelId, ModelConfig config) {
    return requests(wireModelId, config).build(List.of(Message.user("Hi")), List.of(), null).body();
  }

  /** Reads {@code sseBody} through an {@link SseReader} and returns its last {@code Done} event. */
  static StreamEvent.Done drainSseFixture(String sseBody) {
    var inputStream = new ByteArrayInputStream(sseBody.getBytes(StandardCharsets.UTF_8));
    try (var iterator =
        new SseReader(
            inputStream,
            Duration.ofSeconds(5),
            new AnthropicStreamParser("stamp"),
            AnthropicException::new)) {
      StreamEvent.Done done = null;
      while (iterator.hasNext()) {
        var next = iterator.next();
        if (next instanceof StreamEvent.Done d) {
          done = d;
        }
      }
      return done;
    }
  }
}
