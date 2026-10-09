/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.test.ConversationRequestContract;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link ConversationRequestContract} for the Messages API: {@code system} and {@code messages}.
 */
class AnthropicConversationRequestTest extends ConversationRequestContract {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Override
  protected Function<URI, Model> modelAt() {
    return uri ->
        new AnthropicProvider()
            .create(
                AnthropicModelId.CLAUDE_SONNET_4_6.id(),
                ModelConfig.newBuilder()
                    .withApiKey("test-key")
                    .withBaseUrl(uri + "/v1/messages")
                    .build());
  }

  @Override
  protected String reply() {
    return "anthropic/streams/text.sse";
  }

  @Override
  protected String systemPrompt(Map<String, Object> request) {
    return String.join("", texts(request.get("system")).toList());
  }

  @Override
  protected List<String> turnTexts(Map<String, Object> request) {
    return objects(request.get("messages")).stream()
        .flatMap(message -> texts(message.get("content")))
        .toList();
  }

  @Override
  @SuppressWarnings("unchecked")
  protected Map<String, Object> parse(String body) {
    return JSON.readValue(body, Map.class);
  }
}
