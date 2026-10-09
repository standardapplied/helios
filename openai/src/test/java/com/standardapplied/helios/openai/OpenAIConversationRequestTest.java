/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.openai;

import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.test.ConversationRequestContract;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link ConversationRequestContract} for the Responses API: {@code instructions} and {@code
 * input}.
 */
class OpenAIConversationRequestTest extends ConversationRequestContract {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Override
  protected Function<URI, Model> modelAt() {
    return uri ->
        new OpenAIProvider()
            .create(
                OpenAIModelId.GPT_4_1_MINI.id(),
                ModelConfig.newBuilder()
                    .withApiKey("test-key")
                    .withBaseUrl(uri + "/v1/responses")
                    .build());
  }

  @Override
  protected String reply() {
    return "openai/streams/text.sse";
  }

  @Override
  protected String systemPrompt(Map<String, Object> request) {
    return (String) request.get("instructions");
  }

  @Override
  protected List<String> turnTexts(Map<String, Object> request) {
    return objects(request.get("input")).stream()
        .flatMap(item -> texts(item.get("content")))
        .toList();
  }

  @Override
  @SuppressWarnings("unchecked")
  protected Map<String, Object> parse(String body) {
    return JSON.readValue(body, Map.class);
  }
}
