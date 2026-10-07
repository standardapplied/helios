/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import static com.standardapplied.helios.anthropic.AnthropicFixture.model;
import static com.standardapplied.helios.anthropic.AnthropicFixture.requestFor;
import static com.standardapplied.helios.anthropic.AnthropicFixture.requests;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.ThinkingLevel;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnthropicThinkingTest {

  private static String json(Object value) {
    return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(value);
  }

  @Test
  void opus48UsesAdaptiveThinkingShape() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.HIGH)
            .build();
    var model = model(AnthropicModelId.CLAUDE_OPUS_4_8, config);

    var request =
        requests(AnthropicModelId.CLAUDE_OPUS_4_8, config)
            .build(List.of(Message.user("Think")), List.of(), null);

    assertEquals("adaptive", request.thinking().type());
    assertNull(request.thinking().budgetTokens());
    assertEquals("high", request.outputConfig().effort());
    assertEquals(128_000, model.maxOutputTokens());
  }

  @Test
  void unknownClaudeModelDefaultsToAdaptiveThinking() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.MEDIUM)
            .build();
    var requests = requests("claude-some-future-model", config);

    var request = requests.build(List.of(Message.user("Think")), List.of(), null);

    assertEquals("adaptive", request.thinking().type());
    assertNull(
        request.thinking().budgetTokens(),
        "unknown Claude models default to the adaptive shape, not legacy budget_tokens");
    assertEquals("medium", request.outputConfig().effort());
  }

  @Test
  void unknownClaudeModelHonoursAdaptiveOnlyThinkingLevels() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.MAX)
            .build();
    var requests = requests("claude-some-future-model", config);

    var request = requests.build(List.of(Message.user("Think")), List.of(), null);

    assertEquals("adaptive", request.thinking().type());
    assertEquals("max", request.outputConfig().effort());
  }

  @Test
  void buildRequestWithThinking() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.MEDIUM)
            .build();
    var requests = requests(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var request = requests.build(List.of(Message.user("Think")), List.of(), null);

    assertNotNull(request.thinking());
    assertEquals("adaptive", request.thinking().type(), "4.6 deprecates enabled+budget_tokens");
    assertNull(request.thinking().budgetTokens());
    assertEquals("medium", request.outputConfig().effort());
    assertNull(request.temperature(), "temperature is dropped while thinking is active");
  }

  @Test
  void haiku45UsesLegacyBudgetShape() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.MEDIUM)
            .build();
    var requests = requests(AnthropicModelId.CLAUDE_HAIKU_4_5, config);

    var request = requests.build(List.of(Message.user("Think")), List.of(), null);

    assertEquals("enabled", request.thinking().type(), "Haiku 4.5 rejects adaptive");
    assertEquals(10000, request.thinking().budgetTokens());
    assertNull(request.outputConfig(), "legacy shape must NOT carry output_config");
    assertNull(request.temperature());
    assertTrue(request.maxTokens() >= 10000 + 1024);
  }

  @Test
  void haiku45RejectsXhighAndMax() {
    for (var level : List.of(ThinkingLevel.XHIGH, ThinkingLevel.MAX)) {
      var config = ModelConfig.newBuilder().withApiKey("test-key").withThinkingLevel(level).build();
      var requests = requests(AnthropicModelId.CLAUDE_HAIKU_4_5, config);
      var ex =
          assertThrows(
              IllegalArgumentException.class,
              () -> requests.build(List.of(Message.user("Hi")), List.of(), null));
      assertTrue(ex.getMessage().contains("claude-haiku-4-5"), ex.getMessage());
    }
  }

  @Test
  void opus47UsesAdaptiveThinkingShape() {
    // 1.1.5 bug #2: Opus 4.7 rejects "thinking.type=enabled" — must use adaptive + output_config.
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.MEDIUM)
            .build();
    var requests = requests(AnthropicModelId.CLAUDE_OPUS_4_7, config);

    var request = requests.build(List.of(Message.user("Think")), List.of(), null);

    assertNotNull(request.thinking());
    assertEquals("adaptive", request.thinking().type());
    assertNull(
        request.thinking().budgetTokens(),
        "adaptive shape must NOT include budget_tokens — Opus 4.7 rejects it");
    assertNotNull(request.outputConfig(), "adaptive shape requires output_config sibling");
    assertEquals("medium", request.outputConfig().effort());
    assertNull(request.temperature(), "thinking forces null temperature for adaptive too");
  }

  @Test
  void opus47AdaptiveMapsAllThinkingLevels() {
    var levels =
        java.util.Map.of(
            ThinkingLevel.MINIMAL, "low",
            ThinkingLevel.LOW, "low",
            ThinkingLevel.MEDIUM, "medium",
            ThinkingLevel.HIGH, "high");
    for (var entry : levels.entrySet()) {
      var config =
          ModelConfig.newBuilder().withApiKey("test-key").withThinkingLevel(entry.getKey()).build();
      var requests = requests(AnthropicModelId.CLAUDE_OPUS_4_7, config);
      var request = requests.build(List.of(Message.user("Hi")), List.of(), null);
      assertEquals(
          entry.getValue(),
          request.outputConfig().effort(),
          "ThinkingLevel." + entry.getKey() + " must map to effort=" + entry.getValue());
    }
  }

  @Test
  void opus47AdaptiveMapsXhighToWireString() {
    // XHIGH is the second-highest effort tier per Anthropic's adaptive-thinking docs and is
    // available on Opus 4.7 only. The doc specifies the literal wire string "xhigh".
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.XHIGH)
            .build();
    var requests = requests(AnthropicModelId.CLAUDE_OPUS_4_7, config);

    var request = requests.build(List.of(Message.user("Hi")), List.of(), null);

    assertEquals("adaptive", request.thinking().type());
    assertEquals(
        "xhigh",
        request.outputConfig().effort(),
        "XHIGH must produce the literal 'xhigh' wire string Anthropic's API expects");
  }

  @Test
  void opus47AdaptiveMapsMaxToWireString() {
    // MAX is the unbounded effort tier — "always thinks with no constraints on thinking depth".
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.MAX)
            .build();
    var requests = requests(AnthropicModelId.CLAUDE_OPUS_4_7, config);

    var request = requests.build(List.of(Message.user("Hi")), List.of(), null);

    assertEquals("adaptive", request.thinking().type());
    assertEquals("max", request.outputConfig().effort());
  }

  @Test
  void opus47AdaptiveDefaultsDisplayToSummarized() {
    // Anthropic's docs say `thinking.display` silently defaults to "omitted" on Opus 4.7, which
    // would zero out our ModelChunk.ThinkingDelta event stream — silently breaking every caller
    // that watches AssistantThinking events. Helios pins the default to "summarized" so the event
    // contract is preserved; callers who want the omitted-mode latency win opt in explicitly.
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.HIGH)
            .build();
    var requests = requests(AnthropicModelId.CLAUDE_OPUS_4_7, config);

    var request = requests.build(List.of(Message.user("Hi")), List.of(), null);

    assertEquals(
        "summarized",
        request.thinking().display(),
        "Opus 4.7 adaptive default must be summarized so ThinkingDelta events keep flowing");
  }

  @Test
  void opus46RejectsXhighEffort() {
    // XHIGH is Opus 4.7-only per Anthropic's docs. On older models we refuse at build time
    // rather than silently downgrade or wait for the API's 400 — typed exceptions belong at the
    // caller-controlled layer.
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.XHIGH)
            .build();
    var requests = requests(AnthropicModelId.CLAUDE_OPUS_4_6, config);

    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> requests.build(List.of(Message.user("Hi")), List.of(), null));
    assertTrue(
        ex.getMessage().toLowerCase(java.util.Locale.ROOT).contains("xhigh"),
        () -> "exception must name the rejected effort: " + ex.getMessage());
    assertTrue(
        ex.getMessage().contains("Opus 4.7") || ex.getMessage().contains("opus-4-7"),
        () -> "exception must name the supported model: " + ex.getMessage());
  }

  @Test
  void opus46MaxMapsToAdaptiveMaxEffort() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.MAX)
            .build();
    var requests = requests(AnthropicModelId.CLAUDE_OPUS_4_6, config);

    var request = requests.build(List.of(Message.user("Hi")), List.of(), null);

    assertEquals("adaptive", request.thinking().type());
    assertEquals("max", request.outputConfig().effort());
  }

  @Test
  void opus46UsesAdaptiveShapeWithoutBudget() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.MEDIUM)
            .build();
    var requests = requests(AnthropicModelId.CLAUDE_OPUS_4_6, config);

    var request = requests.build(List.of(Message.user("Think")), List.of(), null);

    assertEquals("adaptive", request.thinking().type());
    assertNull(request.thinking().budgetTokens());
    assertEquals("medium", request.outputConfig().effort());
  }

  @Test
  void buildRequestThinkingNoneOmitsConfig() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.NONE)
            .build();
    var requests = requests(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var request = requests.build(List.of(Message.user("Hi")), List.of(), null);

    assertNull(request.thinking());
  }

  @Test
  void buildRequestThinkingMinimal() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.MINIMAL)
            .build();
    var requests = requests(AnthropicModelId.CLAUDE_HAIKU_4_5, config);

    var request = requests.build(List.of(Message.user("Hi")), List.of(), null);

    assertEquals(1024, request.thinking().budgetTokens());
  }

  @Test
  void buildRequestThinkingLow() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.LOW)
            .build();
    var requests = requests(AnthropicModelId.CLAUDE_HAIKU_4_5, config);

    var request = requests.build(List.of(Message.user("Hi")), List.of(), null);

    assertEquals(4096, request.thinking().budgetTokens());
  }

  @Test
  void buildRequestThinkingHigh() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.HIGH)
            .build();
    var requests = requests(AnthropicModelId.CLAUDE_HAIKU_4_5, config);

    var request = requests.build(List.of(Message.user("Hi")), List.of(), null);

    assertEquals(32000, request.thinking().budgetTokens());
  }

  @Test
  void fable5ThinkingLevelSendsSummarizedAdaptiveThinkingWithEffort() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.HIGH)
            .build();
    var requests = requests(AnthropicModelId.CLAUDE_FABLE_5, config);

    var request = requests.build(List.of(Message.user("Think")), List.of(), null);

    assertEquals("adaptive", request.thinking().type());
    assertEquals(
        "summarized",
        request.thinking().display(),
        "the default display is omitted — a caller asking for thinking must get its text");
    assertNull(request.thinking().budgetTokens());
    assertEquals("high", request.outputConfig().effort());
  }

  @Test
  void fable5NoneOmitsThinkingEntirely() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.NONE)
            .build();
    var requests = requests(AnthropicModelId.CLAUDE_FABLE_5, config);

    var request = requests.build(List.of(Message.user("Quick")), List.of(), null);

    assertNull(request.thinking(), "thinking cannot be disabled on Fable 5 — omit the field");
    assertNull(request.outputConfig());
  }

  @Test
  void sonnet5UsesAdaptiveShape() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.XHIGH)
            .build();
    var requests = requests(AnthropicModelId.CLAUDE_SONNET_5, config);

    var request = requests.build(List.of(Message.user("Think")), List.of(), null);

    assertEquals("adaptive", request.thinking().type());
    assertNull(request.thinking().budgetTokens());
    assertEquals("xhigh", request.outputConfig().effort());
  }

  @Test
  void sonnet5NoneSendsExplicitDisabled() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.NONE)
            .build();
    var requests = requests(AnthropicModelId.CLAUDE_SONNET_5, config);

    var request = requests.build(List.of(Message.user("Quick")), List.of(), null);

    assertNotNull(
        request.thinking(),
        "Sonnet 5 runs adaptive thinking when the field is omitted — NONE must send disabled");
    assertEquals("disabled", request.thinking().type());
    assertNull(request.outputConfig());
  }

  @Test
  void opus5NoneDisablesThinkingAndUsesCurrentOutputLimit() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.NONE)
            .build();
    var requests = requests(AnthropicModelId.CLAUDE_OPUS_5, config);

    var request = requests.build(List.of(Message.user("Quick")), List.of(), null);

    assertEquals("disabled", request.thinking().type());
    assertEquals(128_000, request.maxTokens());
  }

  @Test
  void opus5MaxUsesAdaptiveThinkingAndMaxEffort() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.MAX)
            .build();
    var requests = requests(AnthropicModelId.CLAUDE_OPUS_5, config);

    var request = requests.build(List.of(Message.user("Think")), List.of(), null);

    assertEquals("adaptive", request.thinking().type());
    assertEquals("max", request.outputConfig().effort());
  }

  @Test
  void mythos5AlwaysOnThinkingUsesEffortWithoutThinkingField() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.MAX)
            .build();
    var requests = requests(AnthropicModelId.CLAUDE_MYTHOS_5, config);

    var request = requests.build(List.of(Message.user("Think")), List.of(), null);

    assertEquals("adaptive", request.thinking().type());
    assertEquals("summarized", request.thinking().display());
    assertEquals("max", request.outputConfig().effort());
    assertEquals(128_000, request.maxTokens());
  }

  @Test
  void opus55NoneOmitsThinkingAndEffortSoTheApiDefaultsApply() {
    var request = requestFor(AnthropicModelId.CLAUDE_OPUS_5_5, ThinkingLevel.NONE);

    assertNull(request.thinking(), "disabled is a 400 on Opus 5.5 at every effort — omit instead");
    assertNull(request.outputConfig());
    assertEquals(128_000, request.maxTokens());
  }

  @Test
  void opus55EveryThinkingLevelSendsItsEffortExplicitly() {
    var expected =
        Map.of(
            ThinkingLevel.MINIMAL, "low",
            ThinkingLevel.LOW, "low",
            ThinkingLevel.MEDIUM, "medium",
            ThinkingLevel.HIGH, "high",
            ThinkingLevel.XHIGH, "xhigh",
            ThinkingLevel.MAX, "max");
    for (var entry : expected.entrySet()) {
      var request = requestFor(AnthropicModelId.CLAUDE_OPUS_5_5, entry.getKey());

      assertEquals(
          "{\"type\":\"adaptive\",\"display\":\"summarized\"}",
          json(request.thinking()),
          entry.getKey().name());
      assertEquals(entry.getValue(), request.outputConfig().effort(), entry.getKey().name());
    }
  }

  @Test
  void sonnet55NoneSendsBareBetweenTools() {
    var request = requestFor(AnthropicModelId.CLAUDE_SONNET_5_5, ThinkingLevel.NONE);

    assertEquals(
        "{\"type\":\"between_tools\"}",
        json(request.thinking()),
        "between_tools rejects every sibling field, display included");
    assertNull(
        request.outputConfig(),
        "the API default effort (high) is the highest between_tools accepts");
  }

  @Test
  void sonnet55ThinkingLevelsUseAdaptiveNeverBetweenTools() {
    var expected =
        Map.of(
            ThinkingLevel.MINIMAL, "low",
            ThinkingLevel.LOW, "low",
            ThinkingLevel.MEDIUM, "medium",
            ThinkingLevel.HIGH, "high",
            ThinkingLevel.XHIGH, "xhigh",
            ThinkingLevel.MAX, "max");
    for (var entry : expected.entrySet()) {
      var request = requestFor(AnthropicModelId.CLAUDE_SONNET_5_5, entry.getKey());

      assertEquals(
          "{\"type\":\"adaptive\",\"display\":\"summarized\"}",
          json(request.thinking()),
          entry.getKey().name());
      assertEquals(entry.getValue(), request.outputConfig().effort(), entry.getKey().name());
    }
  }

  @Test
  void the55WireIdsResolveToTheirOwnShapeNotTheModelTheyExtend() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.NONE)
            .build();

    var opus =
        requests("claude-opus-5-5", config).build(List.of(Message.user("Hi")), List.of(), null);
    var sonnet =
        requests("claude-sonnet-5-5", config).build(List.of(Message.user("Hi")), List.of(), null);

    assertNull(opus.thinking(), "Opus 5's disabled would 400 on Opus 5.5");
    assertEquals(128_000, opus.maxTokens());
    assertEquals("between_tools", sonnet.thinking().type());
  }

  @Test
  void anUncataloguedReleaseNeverInheritsDisabledThinkingFromTheModelItsIdExtends() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.NONE)
            .build();

    for (var wireId : List.of("claude-opus-5-7", "claude-sonnet-5-9")) {
      var request = requests(wireId, config).build(List.of(Message.user("Hi")), List.of(), null);

      assertNull(request.thinking(), wireId);
      assertEquals(AnthropicRequestBuilder.DEFAULT_MAX_OUTPUT_TOKENS, request.maxTokens(), wireId);
    }
  }
}
