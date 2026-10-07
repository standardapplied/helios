/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.openai;

import static com.standardapplied.helios.openai.OpenAIFixture.requestFor;
import static com.standardapplied.helios.openai.OpenAIFixture.requests;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.ThinkingLevel;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenAIReasoningTest {

  @Test
  void buildRequestWithReasoningMedium() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.MEDIUM)
            .build();
    var requests = requests(OpenAIModelId.O3, config);

    var request = requests.build(List.of(Message.user("Think")), List.of(), null);

    assertNotNull(request.reasoning());
    assertEquals("medium", request.reasoning().effort());
  }

  @Test
  void buildRequestWithReasoningLow() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.LOW)
            .build();
    var requests = requests(OpenAIModelId.O3, config);

    var request = requests.build(List.of(Message.user("Think")), List.of(), null);

    assertNotNull(request.reasoning());
    assertEquals("low", request.reasoning().effort());
  }

  @Test
  void buildRequestWithReasoningMinimal() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.MINIMAL)
            .build();
    var requests = requests(OpenAIModelId.O3, config);

    var request = requests.build(List.of(Message.user("Think")), List.of(), null);

    assertNotNull(request.reasoning());
    assertEquals("low", request.reasoning().effort());
  }

  @Test
  void buildRequestWithReasoningHigh() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.HIGH)
            .build();
    var requests = requests(OpenAIModelId.O3, config);

    var request = requests.build(List.of(Message.user("Think")), List.of(), null);

    assertNotNull(request.reasoning());
    assertEquals("high", request.reasoning().effort());
  }

  @Test
  void gpt56MaxMapsToMaxWireString() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.MAX)
            .build();
    var requests = requests(OpenAIModelId.GPT_5_6, config);

    var request = requests.build(List.of(Message.user("Think")), List.of(), null);

    assertEquals("max", request.reasoning().effort());
  }

  @Test
  void gpt55MaxClampsToXhigh() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.MAX)
            .build();
    var requests = requests(OpenAIModelId.GPT_5_5, config);

    var request = requests.build(List.of(Message.user("Think")), List.of(), null);

    assertEquals("xhigh", request.reasoning().effort());
  }

  @Test
  void gpt56NoneMapsToExplicitNoneEffort() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.NONE)
            .build();
    var requests = requests(OpenAIModelId.GPT_5_6, config);

    var request = requests.build(List.of(Message.user("Quick")), List.of(), null);

    assertNotNull(request.reasoning());
    assertEquals("none", request.reasoning().effort());
  }

  @Test
  void gpt55NoneSendsExplicitNoneInsteadOfDefaultMedium() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.NONE)
            .build();
    var requests = requests(OpenAIModelId.GPT_5_5, config);

    var request = requests.build(List.of(Message.user("Quick")), List.of(), null);

    assertNotNull(request.reasoning(), "omitting reasoning runs gpt-5.5's default medium effort");
    assertEquals("none", request.reasoning().effort());
  }

  @Test
  void gpt6ModelsWithoutNoneEffortPinLowForThinkingNone() {
    for (var modelId : List.of(OpenAIModelId.GPT_6_ASTRA, OpenAIModelId.GPT_6_1_SOL)) {
      var request = requestFor(modelId, ThinkingLevel.NONE);

      assertEquals(
          "low",
          request.reasoning().effort(),
          modelId.id() + " returns a 400 for none; low is its lowest effort");
    }
  }

  @Test
  void gpt6ModelsWithNoneEffortSendItForThinkingNone() {
    for (var modelId : List.of(OpenAIModelId.GPT_6_SOL, OpenAIModelId.GPT_6_LUNA)) {
      assertEquals("none", requestFor(modelId, ThinkingLevel.NONE).reasoning().effort());
    }
  }

  @Test
  void gpt6FamilySendsEveryHigherEffortVerbatim() {
    var expected =
        Map.of(
            ThinkingLevel.MINIMAL, "low",
            ThinkingLevel.LOW, "low",
            ThinkingLevel.MEDIUM, "medium",
            ThinkingLevel.HIGH, "high",
            ThinkingLevel.XHIGH, "xhigh",
            ThinkingLevel.MAX, "max");
    for (var modelId :
        List.of(
            OpenAIModelId.GPT_6_ASTRA,
            OpenAIModelId.GPT_6_1_SOL,
            OpenAIModelId.GPT_6_SOL,
            OpenAIModelId.GPT_6_LUNA)) {
      for (var entry : expected.entrySet()) {
        assertEquals(
            entry.getValue(),
            requestFor(modelId, entry.getKey()).reasoning().effort(),
            modelId.id() + " " + entry.getKey());
      }
    }
  }

  @Test
  void samplingParametersRideAtEffortNone() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.NONE)
            .withTemperature(0.2)
            .withTopP(0.9)
            .build();
    var requests = requests(OpenAIModelId.GPT_6_LUNA, config);

    var request = requests.build(List.of(Message.user("Hi")), List.of(), null);

    assertEquals("none", request.reasoning().effort());
    assertEquals(0.2, request.temperature());
    assertEquals(0.9, request.topP());
  }

  @Test
  void samplingParametersAreDroppedWhenNoneFallsBackToLow() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.NONE)
            .withTemperature(0.2)
            .withTopP(0.9)
            .build();
    var requests = requests(OpenAIModelId.GPT_6_ASTRA, config);

    var request = requests.build(List.of(Message.user("Hi")), List.of(), null);

    assertEquals("low", request.reasoning().effort());
    assertNull(request.temperature());
    assertNull(request.topP());
  }

  @Test
  void reasoningRequestsNeverCarrySamplingParameters() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.MEDIUM)
            .withTemperature(0.7)
            .withTopP(0.9)
            .build();
    var requests = requests(OpenAIModelId.GPT_6_ASTRA, config);

    var request = requests.build(List.of(Message.user("Think")), List.of(), null);

    assertNull(request.temperature());
    assertNull(request.topP(), "top_p with a reasoning effort returns a 400 on the GPT-6 family");
  }

  @Test
  void gpt54MiniXhighIsSentVerbatim() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.XHIGH)
            .build();
    var requests = requests(OpenAIModelId.GPT_5_4_MINI, config);

    var request = requests.build(List.of(Message.user("Think")), List.of(), null);

    assertEquals("xhigh", request.reasoning().effort());
  }

  @Test
  void noneOmitsReasoningOnModelsWithoutExplicitNone() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.NONE)
            .build();
    var requests = requests(OpenAIModelId.O4_MINI, config);

    var request = requests.build(List.of(Message.user("Quick")), List.of(), null);

    assertNull(request.reasoning());
  }

  @Test
  void gpt55XhighMapsToXhighWireString() {
    // Per OpenAI's deployment-checklist + gpt-5.5 model page, gpt-5.5 reasoning.effort accepts
    // none/low/medium/high/xhigh. Helios's XHIGH must round-trip to the literal "xhigh", not
    // clamp to "high".
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.XHIGH)
            .build();
    var requests = requests(OpenAIModelId.GPT_5_5, config);

    var request = requests.build(List.of(Message.user("Hi")), List.of(), null);

    assertEquals(
        "xhigh",
        request.reasoning().effort(),
        "gpt-5.5 must receive the literal 'xhigh' wire string");
  }

  @Test
  void gpt54XhighMapsToXhighWireString() {
    // gpt-5.4 model page documents the same five-tier set as gpt-5.5.
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.XHIGH)
            .build();
    var requests = requests(OpenAIModelId.GPT_5_4, config);

    var request = requests.build(List.of(Message.user("Hi")), List.of(), null);

    assertEquals("xhigh", request.reasoning().effort());
  }

  @Test
  void gpt55MaxClampsToXhighWireString() {
    // OpenAI has no native "max" tier — Helios's MAX maps to OpenAI's highest available, which
    // is xhigh on gpt-5.5. Distinct from "high" so callers get the strongest reasoning OpenAI
    // exposes.
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.MAX)
            .build();
    var requests = requests(OpenAIModelId.GPT_5_5, config);

    var request = requests.build(List.of(Message.user("Hi")), List.of(), null);

    assertEquals(
        "xhigh",
        request.reasoning().effort(),
        "MAX must clamp to OpenAI's highest tier (xhigh on gpt-5.5), not stop at 'high'");
  }

  @Test
  void o3XhighClampsToHighWireString() {
    // o-series reasoning models (o3, o4-mini) are not documented to accept "xhigh" — only
    // low/medium/high are confirmed via OpenAI's docs. Helios clamps XHIGH/MAX to "high" here
    // until OpenAI publishes wider support. Conservative dispatch keeps requests valid.
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.XHIGH)
            .build();
    var requests = requests(OpenAIModelId.O3, config);

    var request = requests.build(List.of(Message.user("Hi")), List.of(), null);

    assertEquals("high", request.reasoning().effort());
  }

  @Test
  void o4MiniMaxClampsToHighWireString() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.MAX)
            .build();
    var requests = requests(OpenAIModelId.O4_MINI, config);

    var request = requests.build(List.of(Message.user("Hi")), List.of(), null);

    assertEquals("high", request.reasoning().effort());
  }

  @Test
  void buildRequestReasoningNoneOmitsConfig() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.NONE)
            .build();
    var requests = requests(OpenAIModelId.O3, config);

    var request = requests.build(List.of(Message.user("Hi")), List.of(), null);

    assertNull(request.reasoning());
  }

  @Test
  void buildRequestReasoningNullsTemperature() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withTemperature(0.7)
            .withThinkingLevel(ThinkingLevel.HIGH)
            .build();
    var requests = requests(OpenAIModelId.O3, config);

    var request = requests.build(List.of(Message.user("Think")), List.of(), null);

    assertNull(request.temperature());
    assertNotNull(request.reasoning());
  }
}
