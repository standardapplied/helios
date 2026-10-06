/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.anthropic.api.AnthropicJson;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.test.Golden;
import com.standardapplied.helios.core.test.ModelHarness;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The prompt-cache breakpoint a model puts on its system prompt, read from the request it sends, so
 * a test observes a model's cache policy through its behavior.
 */
final class SentCacheControl {

  /** The breakpoint of the short-lived (five-minute) policy: Anthropic's implicit ttl. */
  static final Map<String, String> SHORT_LIVED = Map.of("type", "ephemeral");

  /** The breakpoint of the long-lived (one-hour) policy. */
  static final Map<String, String> LONG_LIVED = Map.of("type", "ephemeral", "ttl", "1h");

  private static final List<String> REPLY = List.of(Golden.read("anthropic/requests-reply.sse"));

  private SentCacheControl() {}

  /**
   * The {@code cache_control} on the system prompt of the request the model {@code create} builds
   * sends, or {@code null} when it sends the system prompt uncached.
   */
  static Object onSystemPrompt(Function<ModelConfig, Model> create) {
    var turn = List.of(Message.system("Be helpful"), Message.user("Hi"));
    var body =
        ModelHarness.exchange(
                REPLY, uri -> create.apply(at(uri + "/v1/messages")), m -> m.chat(turn, List.of()))
            .getLast()
            .body();
    var system = AnthropicJson.LENIENT.readValue(body, Map.class).get("system");
    return system instanceof List<?> blocks
        ? ((Map<?, ?>) blocks.getFirst()).get("cache_control")
        : null;
  }

  private static ModelConfig at(String baseUrl) {
    return ModelConfig.newBuilder().withApiKey("test-key").withBaseUrl(baseUrl).build();
  }
}
