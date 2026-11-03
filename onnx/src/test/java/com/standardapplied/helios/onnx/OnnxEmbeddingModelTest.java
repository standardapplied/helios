/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.onnx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.Result;
import com.standardapplied.helios.core.embedding.EmbeddingConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@link OnnxEmbeddingModel} behaviour that does not depend on the model: Nomic's files run under a
 * spec whose eight-token sequence length makes truncation cheap to reach.
 */
class OnnxEmbeddingModelTest {

  private static final String NOMIC = OnnxModelId.NOMIC_EMBED_V1_5.id();
  private static final OnnxModelSpec EIGHT_TOKENS =
      new OnnxModelSpec(OnnxModelSpec.ModelType.ENCODER, "onnx", "model", 8, 768, "", "");

  private static OnnxEmbeddingModel model;

  @BeforeAll
  static void open() {
    model = nomic();
  }

  @AfterAll
  static void close() {
    model.close();
  }

  @Test
  void textBeyondTheSequenceLengthIsTruncated() {
    var opening = "the quick brown fox jumps over the lazy dog";

    var nearTheRiver = unwrap(model.embed(opening + " near the river bank"));
    var inTheMeadow = unwrap(model.embed(opening + " in a quiet meadow at dawn"));

    assertEquals(768, nearTheRiver.length);
    assertArrayEquals(nearTheRiver, inTheMeadow);
  }

  @Test
  void aBlankQueryIsRefused() {
    var failure = assertInstanceOf(Result.Failure.class, model.embedQuery(" "));

    assertEquals("Query must not be null or empty", failure.error());
  }

  @Test
  void aClosedModelFailsToEmbed() {
    var closed = nomic();
    closed.close();

    var single = assertInstanceOf(Result.Failure.class, closed.embed("a closed model"));
    var batch = assertInstanceOf(Result.Failure.class, closed.embedBatch(new String[] {"text"}));

    assertTrue(single.error().startsWith("Failed to generate embedding: "), single.error());
    assertEquals(single.error(), batch.error());
  }

  private static OnnxEmbeddingModel nomic() {
    return OnnxEmbeddingModel.create(NOMIC, EmbeddingConfig.defaults(), EIGHT_TOKENS);
  }

  private static float[] unwrap(Result<float[]> result) {
    return ((Result.Success<float[]>) assertInstanceOf(Result.Success.class, result)).value();
  }
}
