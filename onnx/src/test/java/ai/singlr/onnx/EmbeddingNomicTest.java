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

class EmbeddingNomicTest {

  private static EmbeddingModel model;

  @BeforeAll
  static void setUp() {
    var provider = new OnnxEmbeddingProvider();
    var config = EmbeddingConfig.defaults();
    model = provider.create("nomic-ai/nomic-embed-text-v1.5", config);
  }

  @AfterAll
  static void tearDown() {
    if (model != null) {
      model.close();
    }
  }

  @Test
  void embedSingleText() {
    var result = model.embed("Interests: Technology, Healthcare, AI\nSkills: Leadership");

    assertInstanceOf(Result.Success.class, result);
    var embedding = ((Result.Success<float[]>) result).value();
    assertEquals(768, embedding.length);
  }

  @Test
  void embedEmptyTextReturnsFailure() {
    var result = model.embed("");
    assertInstanceOf(Result.Failure.class, result);
  }

  @Test
  void embedNullTextReturnsFailure() {
    var result = model.embed(null);
    assertInstanceOf(Result.Failure.class, result);
  }

  @Test
  void semanticSimilarity() {
    var emb1 = unwrap(model.embed("A man is eating food."));
    var emb2 = unwrap(model.embed("A person is consuming a meal."));
    var emb3 = unwrap(model.embed("The weather is nice today."));

    var similarity12 = cosineSimilarity(emb1, emb2);
    var similarity13 = cosineSimilarity(emb1, emb3);

    assertTrue(
        similarity12 > similarity13,
        "Semantically similar texts should have higher similarity: %f vs %f"
            .formatted(similarity12, similarity13));
  }

  @Test
  void embedBatch() {
    var texts =
        new String[] {
          "A man is eating food.", "A person is consuming a meal.", "The weather is nice today."
        };

    var result = model.embedBatch(texts);

    assertInstanceOf(Result.Success.class, result);
    var embeddings = ((Result.Success<float[][]>) result).value();
    assertEquals(3, embeddings.length);
    for (var embedding : embeddings) {
      assertEquals(768, embedding.length);
    }
  }

  @Test
  void embedBatchNullArrayReturnsFailure() {
    var result = model.embedBatch(null);
    assertInstanceOf(Result.Failure.class, result);
  }

  @Test
  void embedBatchEmptyArrayReturnsFailure() {
    var result = model.embedBatch(new String[0]);
    assertInstanceOf(Result.Failure.class, result);
  }

  @Test
  void embedBatchNullElementReturnsFailure() {
    var texts = new String[] {"Valid text", null, "Another valid text"};

    var result = model.embedBatch(texts);
    assertInstanceOf(Result.Failure.class, result);
    var failure = (Result.Failure<float[][]>) result;
    assertTrue(failure.error().contains("index 1"));
  }

  @Test
  void embeddingDimension() {
    assertEquals(768, model.embeddingDimension());
  }

  @Test
  void modelName() {
    assertEquals("nomic-ai/nomic-embed-text-v1.5", model.modelName());
  }

  @Test
  void identicalTextsProduceSameEmbeddings() {
    var emb1 = unwrap(model.embed("A man is eating food."));
    var emb2 = unwrap(model.embed("A man is eating food."));

    assertEquals(1.0f, cosineSimilarity(emb1, emb2), 0.0001f);
  }

  @Test
  void embeddingIsNormalized() {
    var embedding = unwrap(model.embed("This is a test sentence."));

    var norm = 0.0f;
    for (var value : embedding) {
      norm += value * value;
    }
    norm = (float) Math.sqrt(norm);

    assertEquals(1.0f, norm, 0.0001f);
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
