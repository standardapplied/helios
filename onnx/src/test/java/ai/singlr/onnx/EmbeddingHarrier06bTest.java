/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.Result;
import ai.singlr.core.embedding.EmbeddingConfig;
import ai.singlr.core.embedding.EmbeddingModel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class EmbeddingHarrier06bTest {

  private static EmbeddingModel harrierModel;

  @BeforeAll
  static void setUp() {
    var provider = new OnnxEmbeddingProvider();
    harrierModel =
        provider.create(OnnxModelId.HARRIER_OSS_V1_0_6B.id(), EmbeddingConfig.defaults());
  }

  @AfterAll
  static void tearDown() {
    if (harrierModel != null) {
      harrierModel.close();
    }
  }

  @Test
  void embeddingDimension() {
    assertEquals(1024, harrierModel.embeddingDimension());
  }

  @Test
  void embedDocument() {
    var result =
        harrierModel.embedDocument(
            "A software engineer passionate about building AI-powered applications.");

    assertInstanceOf(Result.Success.class, result);
    var embedding = ((Result.Success<float[]>) result).value();
    assertEquals(1024, embedding.length);
  }

  @Test
  void embedQuery() {
    var result = harrierModel.embedQuery("AI engineer looking for startup opportunities");

    assertInstanceOf(Result.Success.class, result);
    var embedding = ((Result.Success<float[]>) result).value();
    assertEquals(1024, embedding.length);
  }

  @Test
  void embeddingIsNormalized() {
    var embedding = unwrap(harrierModel.embedDocument("This is a test document."));

    var norm = 0.0f;
    for (var value : embedding) {
      norm += value * value;
    }
    norm = (float) Math.sqrt(norm);

    assertEquals(1.0f, norm, 0.0001f);
  }

  @Test
  void customQueryPrefixOverridesDefault() {
    var query = "looking for a climate tech founder";
    var defaultEmb = unwrap(harrierModel.embedQuery(query));
    var customEmb =
        unwrap(
            harrierModel.embedQuery(
                query,
                "Instruct: Given a brief description, find the matching member's biography"
                    + "\nQuery: "));

    var sim = cosineSimilarity(defaultEmb, customEmb);
    assertTrue(
        sim < 0.999f,
        "Custom prefix should produce a measurably different embedding (cosine=%.4f)"
            .formatted(sim));
  }

  private static float[] unwrap(Result<float[]> result) {
    assertInstanceOf(Result.Success.class, result);
    return ((Result.Success<float[]>) result).value();
  }

  private static float cosineSimilarity(float[] a, float[] b) {
    var dotProduct = 0.0f;
    var normA = 0.0f;
    var normB = 0.0f;
    for (var i = 0; i < a.length; i++) {
      dotProduct += a[i] * b[i];
      normA += a[i] * a[i];
      normB += b[i] * b[i];
    }
    return dotProduct / (float) (Math.sqrt(normA) * Math.sqrt(normB));
  }
}
