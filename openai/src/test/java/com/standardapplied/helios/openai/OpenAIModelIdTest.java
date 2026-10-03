/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class OpenAIModelIdTest {

  @Test
  void gpt55Id() {
    assertEquals("gpt-5.5", OpenAIModelId.GPT_5_5.id());
  }

  @Test
  void gpt55ContextWindow() {
    assertEquals(1_050_000, OpenAIModelId.GPT_5_5.contextWindow());
  }

  @Test
  void gpt54Id() {
    assertEquals("gpt-5.4", OpenAIModelId.GPT_5_4.id());
  }

  @Test
  void gpt54MiniId() {
    assertEquals("gpt-5.4-mini", OpenAIModelId.GPT_5_4_MINI.id());
  }

  @Test
  void gpt54NanoId() {
    assertEquals("gpt-5.4-nano", OpenAIModelId.GPT_5_4_NANO.id());
  }

  @Test
  void gpt41Id() {
    assertEquals("gpt-4.1", OpenAIModelId.GPT_4_1.id());
  }

  @Test
  void gpt41MiniId() {
    assertEquals("gpt-4.1-mini", OpenAIModelId.GPT_4_1_MINI.id());
  }

  @Test
  void gpt41NanoId() {
    assertEquals("gpt-4.1-nano", OpenAIModelId.GPT_4_1_NANO.id());
  }

  @Test
  void gpt4oId() {
    assertEquals("gpt-4o", OpenAIModelId.GPT_4O.id());
  }

  @Test
  void gpt4oMiniId() {
    assertEquals("gpt-4o-mini", OpenAIModelId.GPT_4O_MINI.id());
  }

  @Test
  void o3Id() {
    assertEquals("o3", OpenAIModelId.O3.id());
  }

  @Test
  void o4MiniId() {
    assertEquals("o4-mini", OpenAIModelId.O4_MINI.id());
  }

  @Test
  void gpt54ContextWindow() {
    assertEquals(1_050_000, OpenAIModelId.GPT_5_4.contextWindow());
  }

  @Test
  void gpt54MaxOutputTokens() {
    assertEquals(128_000, OpenAIModelId.GPT_5_4.maxOutputTokens());
  }

  @Test
  void gpt55MaxOutputTokens() {
    assertEquals(128_000, OpenAIModelId.GPT_5_5.maxOutputTokens());
  }

  @Test
  void gpt54MiniContextAndMaxOutput() {
    assertEquals(400_000, OpenAIModelId.GPT_5_4_MINI.contextWindow());
    assertEquals(128_000, OpenAIModelId.GPT_5_4_MINI.maxOutputTokens());
  }

  @Test
  void gpt54NanoContextAndMaxOutput() {
    assertEquals(400_000, OpenAIModelId.GPT_5_4_NANO.contextWindow());
    assertEquals(128_000, OpenAIModelId.GPT_5_4_NANO.maxOutputTokens());
  }

  @Test
  void gpt4oContextWindow() {
    assertEquals(128_000, OpenAIModelId.GPT_4O.contextWindow());
  }

  @Test
  void o3ContextWindow() {
    assertEquals(200_000, OpenAIModelId.O3.contextWindow());
  }

  @Test
  void fromIdFindsKnownModel() {
    var model = OpenAIModelId.fromId("gpt-4o");
    assertNotNull(model);
    assertEquals(OpenAIModelId.GPT_4O, model);
  }

  @Test
  void fromIdReturnsNullForNull() {
    assertNull(OpenAIModelId.fromId(null));
  }

  @Test
  void fromIdReturnsNullForBlank() {
    assertNull(OpenAIModelId.fromId("   "));
  }

  @Test
  void fromIdReturnsNullForUnknown() {
    assertNull(OpenAIModelId.fromId("davinci-003"));
  }

  @Test
  void isSupportedKnownModel() {
    assertTrue(OpenAIModelId.isSupported("gpt-4.1"));
  }

  @Test
  void isSupportedUnknownModel() {
    assertFalse(OpenAIModelId.isSupported("claude-sonnet-4-6"));
  }

  @Test
  void gpt56FamilyIsSupported() {
    assertEquals("gpt-5.6", OpenAIModelId.GPT_5_6.id());
    assertEquals("gpt-5.6-sol", OpenAIModelId.GPT_5_6_SOL.id());
    assertEquals(1_050_000, OpenAIModelId.GPT_5_6.contextWindow());
    assertEquals(128_000, OpenAIModelId.GPT_5_6.maxOutputTokens());
    assertEquals("gpt-5.6-terra", OpenAIModelId.GPT_5_6_TERRA.id());
    assertEquals("gpt-5.6-luna", OpenAIModelId.GPT_5_6_LUNA.id());
    assertEquals(OpenAIModelId.GPT_5_6_SOL, OpenAIModelId.fromId("gpt-5.6-sol"));
  }

  @Test
  void gpt6FamilyIsCatalogued() {
    assertEquals("gpt-6-astra", OpenAIModelId.GPT_6_ASTRA.id());
    assertEquals("gpt-6.1-sol", OpenAIModelId.GPT_6_1_SOL.id());
    assertEquals("gpt-6-sol", OpenAIModelId.GPT_6_SOL.id());
    assertEquals("gpt-6-luna", OpenAIModelId.GPT_6_LUNA.id());
    for (var model :
        List.of(
            OpenAIModelId.GPT_6_ASTRA,
            OpenAIModelId.GPT_6_1_SOL,
            OpenAIModelId.GPT_6_SOL,
            OpenAIModelId.GPT_6_LUNA)) {
      assertEquals(1_050_000, model.contextWindow(), model.id());
      assertEquals(128_000, model.maxOutputTokens(), model.id());
      assertEquals(model, OpenAIModelId.fromId(model.id()));
      assertTrue(OpenAIModelId.isSupported(model.id()));
    }
  }

  @Test
  void gpt6EffortSupportSeparatesModelsThatRejectNone() {
    assertEquals(
        OpenAIModelId.EffortSupport.FULL_WITHOUT_NONE, OpenAIModelId.GPT_6_ASTRA.effortSupport());
    assertEquals(
        OpenAIModelId.EffortSupport.FULL_WITHOUT_NONE, OpenAIModelId.GPT_6_1_SOL.effortSupport());
    assertEquals(OpenAIModelId.EffortSupport.FULL, OpenAIModelId.GPT_6_SOL.effortSupport());
    assertEquals(OpenAIModelId.EffortSupport.FULL, OpenAIModelId.GPT_6_LUNA.effortSupport());
  }

  @Test
  void effortSupportTiers() {
    assertEquals(OpenAIModelId.EffortSupport.FULL, OpenAIModelId.GPT_5_6.effortSupport());
    assertEquals(OpenAIModelId.EffortSupport.FULL, OpenAIModelId.GPT_5_6_SOL.effortSupport());
    assertEquals(OpenAIModelId.EffortSupport.FULL, OpenAIModelId.GPT_5_6_TERRA.effortSupport());
    assertEquals(OpenAIModelId.EffortSupport.EXTENDED, OpenAIModelId.GPT_5_5.effortSupport());
    assertEquals(OpenAIModelId.EffortSupport.EXTENDED, OpenAIModelId.GPT_5_4.effortSupport());
    assertEquals(OpenAIModelId.EffortSupport.STANDARD, OpenAIModelId.O3.effortSupport());
    assertEquals(OpenAIModelId.EffortSupport.STANDARD, OpenAIModelId.GPT_4O.effortSupport());
  }

  @Test
  void allModelsHaveIds() {
    for (var model : OpenAIModelId.values()) {
      assertNotNull(model.id());
      assertFalse(model.id().isBlank());
      assertTrue(model.contextWindow() > 0);
    }
  }

  @Test
  void effortSupportPerModel() {
    assertEquals(OpenAIModelId.EffortSupport.FULL, OpenAIModelId.GPT_5_6.effortSupport());
    assertEquals(OpenAIModelId.EffortSupport.EXTENDED, OpenAIModelId.GPT_5_5.effortSupport());
    assertEquals(OpenAIModelId.EffortSupport.EXTENDED, OpenAIModelId.GPT_5_4.effortSupport());
    assertEquals(OpenAIModelId.EffortSupport.EXTENDED, OpenAIModelId.GPT_5_4_MINI.effortSupport());
    assertEquals(OpenAIModelId.EffortSupport.EXTENDED, OpenAIModelId.GPT_5_4_NANO.effortSupport());
    assertEquals(OpenAIModelId.EffortSupport.STANDARD, OpenAIModelId.O3.effortSupport());
    assertEquals(OpenAIModelId.EffortSupport.STANDARD, OpenAIModelId.GPT_4_1.effortSupport());
  }
}
