/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.openai;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.openai.api.ResponsesRequest;
import java.util.List;

/** What the OpenAI unit tests share: a model or request builder for a model and configuration. */
final class OpenAIFixture {

  private OpenAIFixture() {}

  static Model createModel() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    return createModel(OpenAIModelId.GPT_4O, config);
  }

  static Model createModel(OpenAIModelId modelId, ModelConfig config) {
    return new OpenAIProvider().create(modelId.id(), config);
  }

  static OpenAIRequestBuilder requests(OpenAIModelId modelId, ModelConfig config) {
    return new OpenAIRequestBuilder(modelId.id(), modelId, config);
  }

  static OpenAIRequestBuilder requests() {
    return requests(OpenAIModelId.GPT_4O, ModelConfig.newBuilder().withApiKey("test-key").build());
  }

  /** The request for a one-message conversation with {@code wireModelId} under {@code config}. */
  static ResponsesRequest requestFor(String wireModelId, ModelConfig config) {
    return new OpenAIRequestBuilder(wireModelId, OpenAIModelId.fromId(wireModelId), config)
        .build(List.of(Message.user("Hi")), List.of(), null);
  }
}
