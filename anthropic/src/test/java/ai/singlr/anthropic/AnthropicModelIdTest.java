/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AnthropicModelIdTest {

  @Test
  void enumHasCorrectId() {
    assertEquals("claude-opus-5", AnthropicModelId.CLAUDE_OPUS_5.id());
    assertEquals("claude-mythos-5", AnthropicModelId.CLAUDE_MYTHOS_5.id());
    assertEquals("claude-opus-4-8", AnthropicModelId.CLAUDE_OPUS_4_8.id());
    assertEquals("claude-opus-4-7", AnthropicModelId.CLAUDE_OPUS_4_7.id());
    assertEquals("claude-opus-4-6", AnthropicModelId.CLAUDE_OPUS_4_6.id());
    assertEquals("claude-sonnet-4-6", AnthropicModelId.CLAUDE_SONNET_4_6.id());
  }

  @Test
  void opus48UsesAdaptiveThinking() {
    assertTrue(AnthropicModelId.CLAUDE_OPUS_4_8.usesAdaptiveThinking());
    assertEquals(128_000, AnthropicModelId.CLAUDE_OPUS_4_8.maxOutputTokens());
  }

  @Test
  void legacyFamilyMaxOutputMatchesPublishedLimits() {
    assertEquals(128_000, AnthropicModelId.CLAUDE_OPUS_4_7.maxOutputTokens());
    assertEquals(128_000, AnthropicModelId.CLAUDE_OPUS_4_6.maxOutputTokens());
    assertEquals(128_000, AnthropicModelId.CLAUDE_SONNET_4_6.maxOutputTokens());
  }

  @Test
  void haiku45IsCataloguedWithLegacyThinkingShape() {
    assertEquals("claude-haiku-4-5", AnthropicModelId.CLAUDE_HAIKU_4_5.id());
    assertEquals(200_000, AnthropicModelId.CLAUDE_HAIKU_4_5.contextWindow());
    assertEquals(64_000, AnthropicModelId.CLAUDE_HAIKU_4_5.maxOutputTokens());
    assertEquals(
        AnthropicModelId.ThinkingShape.LEGACY_BUDGET,
        AnthropicModelId.CLAUDE_HAIKU_4_5.thinkingShape());
    assertTrue(AnthropicModelId.isSupported("claude-haiku-4-5"));
    assertEquals(
        AnthropicModelId.CLAUDE_HAIKU_4_5,
        AnthropicModelId.fromWireId("claude-haiku-4-5-20251001"));
  }

  @Test
  void contextWindowValues() {
    assertEquals(1_000_000, AnthropicModelId.CLAUDE_OPUS_5.contextWindow());
    assertEquals(1_000_000, AnthropicModelId.CLAUDE_MYTHOS_5.contextWindow());
    assertEquals(1_000_000, AnthropicModelId.CLAUDE_OPUS_4_8.contextWindow());
    assertEquals(1_000_000, AnthropicModelId.CLAUDE_OPUS_4_7.contextWindow());
    assertEquals(1_000_000, AnthropicModelId.CLAUDE_OPUS_4_6.contextWindow());
    assertEquals(1_000_000, AnthropicModelId.CLAUDE_SONNET_4_6.contextWindow());
  }

  @Test
  void fromIdReturnsCorrectModel() {
    assertEquals(AnthropicModelId.CLAUDE_OPUS_5, AnthropicModelId.fromId("claude-opus-5"));
    assertEquals(AnthropicModelId.CLAUDE_MYTHOS_5, AnthropicModelId.fromId("claude-mythos-5"));
    assertEquals(AnthropicModelId.CLAUDE_OPUS_4_8, AnthropicModelId.fromId("claude-opus-4-8"));
    assertEquals(AnthropicModelId.CLAUDE_OPUS_4_7, AnthropicModelId.fromId("claude-opus-4-7"));
    assertEquals(AnthropicModelId.CLAUDE_OPUS_4_6, AnthropicModelId.fromId("claude-opus-4-6"));
    assertEquals(AnthropicModelId.CLAUDE_SONNET_4_6, AnthropicModelId.fromId("claude-sonnet-4-6"));
  }

  @Test
  void hasClaudePrefixMatchesClaudeIds() {
    assertTrue(AnthropicModelId.hasClaudePrefix("claude-opus-4-8"));
    assertTrue(AnthropicModelId.hasClaudePrefix("claude-opus-9-9"));
    assertTrue(AnthropicModelId.hasClaudePrefix("claude-some-future-model"));
  }

  @Test
  void hasClaudePrefixRejectsNonClaudeAndBlank() {
    assertFalse(AnthropicModelId.hasClaudePrefix("gpt-5"));
    assertFalse(AnthropicModelId.hasClaudePrefix("gemini-3-flash-preview"));
    assertFalse(AnthropicModelId.hasClaudePrefix("Claude-opus-4-8"));
    assertFalse(AnthropicModelId.hasClaudePrefix(null));
    assertFalse(AnthropicModelId.hasClaudePrefix(""));
    assertFalse(AnthropicModelId.hasClaudePrefix("   "));
  }

  @Test
  void fromIdReturnsNullForUnknown() {
    assertNull(AnthropicModelId.fromId("unknown-model"));
  }

  @Test
  void fromIdReturnsNullForNull() {
    assertNull(AnthropicModelId.fromId(null));
  }

  @Test
  void fromIdReturnsNullForBlank() {
    assertNull(AnthropicModelId.fromId(""));
    assertNull(AnthropicModelId.fromId("   "));
  }

  @Test
  void isSupportedReturnsTrueForKnownModels() {
    assertTrue(AnthropicModelId.isSupported("claude-opus-5"));
    assertTrue(AnthropicModelId.isSupported("claude-mythos-5"));
    assertTrue(AnthropicModelId.isSupported("claude-opus-4-8"));
    assertTrue(AnthropicModelId.isSupported("claude-opus-4-7"));
    assertTrue(AnthropicModelId.isSupported("claude-opus-4-6"));
    assertTrue(AnthropicModelId.isSupported("claude-sonnet-4-6"));
  }

  @Test
  void fable5AndSonnet5AreSupported() {
    assertEquals("claude-fable-5", AnthropicModelId.CLAUDE_FABLE_5.id());
    assertEquals(1_000_000, AnthropicModelId.CLAUDE_FABLE_5.contextWindow());
    assertEquals(128_000, AnthropicModelId.CLAUDE_FABLE_5.maxOutputTokens());
    assertEquals("claude-sonnet-5", AnthropicModelId.CLAUDE_SONNET_5.id());
    assertEquals(1_000_000, AnthropicModelId.CLAUDE_SONNET_5.contextWindow());
    assertEquals(128_000, AnthropicModelId.CLAUDE_SONNET_5.maxOutputTokens());
  }

  @Test
  void opus5AndMythos5MetadataMatchesCurrentModels() {
    assertEquals(128_000, AnthropicModelId.CLAUDE_OPUS_5.maxOutputTokens());
    assertEquals(128_000, AnthropicModelId.CLAUDE_MYTHOS_5.maxOutputTokens());
    assertEquals(
        AnthropicModelId.ThinkingShape.ADAPTIVE_DEFAULT_ON,
        AnthropicModelId.CLAUDE_OPUS_5.thinkingShape());
    assertEquals(
        AnthropicModelId.ThinkingShape.ALWAYS_ON, AnthropicModelId.CLAUDE_MYTHOS_5.thinkingShape());
  }

  @Test
  void thinkingShapesPerModel() {
    assertEquals(
        AnthropicModelId.ThinkingShape.ALWAYS_ON, AnthropicModelId.CLAUDE_FABLE_5.thinkingShape());
    assertEquals(
        AnthropicModelId.ThinkingShape.ALWAYS_ON, AnthropicModelId.CLAUDE_MYTHOS_5.thinkingShape());
    assertEquals(
        AnthropicModelId.ThinkingShape.ADAPTIVE_DEFAULT_ON,
        AnthropicModelId.CLAUDE_OPUS_5.thinkingShape());
    assertEquals(
        AnthropicModelId.ThinkingShape.ADAPTIVE_DEFAULT_ON,
        AnthropicModelId.CLAUDE_SONNET_5.thinkingShape());
    assertEquals(
        AnthropicModelId.ThinkingShape.ADAPTIVE, AnthropicModelId.CLAUDE_OPUS_4_8.thinkingShape());
    assertEquals(
        AnthropicModelId.ThinkingShape.ADAPTIVE, AnthropicModelId.CLAUDE_OPUS_4_7.thinkingShape());
    assertEquals(
        AnthropicModelId.ThinkingShape.ADAPTIVE_WITHOUT_XHIGH,
        AnthropicModelId.CLAUDE_OPUS_4_6.thinkingShape());
    assertEquals(
        AnthropicModelId.ThinkingShape.ADAPTIVE_WITHOUT_XHIGH,
        AnthropicModelId.CLAUDE_SONNET_4_6.thinkingShape());
    assertEquals(
        AnthropicModelId.ThinkingShape.LEGACY_BUDGET,
        AnthropicModelId.CLAUDE_HAIKU_4_5.thinkingShape());
  }

  @Test
  void samplingParametersAcceptedOnlyWhereDocumented() {
    assertTrue(AnthropicModelId.ThinkingShape.LEGACY_BUDGET.acceptsSamplingParameters());
    assertTrue(AnthropicModelId.ThinkingShape.ADAPTIVE_WITHOUT_XHIGH.acceptsSamplingParameters());
    assertFalse(AnthropicModelId.ThinkingShape.ADAPTIVE.acceptsSamplingParameters());
    assertFalse(AnthropicModelId.ThinkingShape.ADAPTIVE_DEFAULT_ON.acceptsSamplingParameters());
    assertFalse(AnthropicModelId.ThinkingShape.ALWAYS_ON.acceptsSamplingParameters());
  }

  @Test
  @SuppressWarnings("deprecation")
  void deprecatedAdaptiveFlagDerivesFromShape() {
    assertTrue(AnthropicModelId.CLAUDE_FABLE_5.usesAdaptiveThinking());
    assertTrue(AnthropicModelId.CLAUDE_OPUS_4_8.usesAdaptiveThinking());
    assertTrue(AnthropicModelId.CLAUDE_SONNET_4_6.usesAdaptiveThinking());
    assertFalse(AnthropicModelId.CLAUDE_HAIKU_4_5.usesAdaptiveThinking());
  }

  @Test
  void isSupportedReturnsFalseForUnknownModels() {
    assertFalse(AnthropicModelId.isSupported("unknown-model"));
    assertFalse(AnthropicModelId.isSupported(null));
    assertFalse(AnthropicModelId.isSupported(""));
  }

  @Test
  void isSupportedReturnsFalseForOtherProviderModels() {
    assertFalse(AnthropicModelId.isSupported("gpt-4"));
    assertFalse(AnthropicModelId.isSupported("gemini-3-flash-preview"));
  }
}
