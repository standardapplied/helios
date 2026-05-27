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

class EmbeddingHarrierTest {

  private static EmbeddingModel harrierModel;

  @BeforeAll
  static void setUp() {
    var provider = new OnnxEmbeddingProvider();
    harrierModel =
        provider.create(OnnxModelId.HARRIER_OSS_V1_270M.id(), EmbeddingConfig.defaults());
  }

  @AfterAll
  static void tearDown() {
    if (harrierModel != null) {
      harrierModel.close();
    }
  }

  @Test
  void embeddingDimension() {
    assertEquals(640, harrierModel.embeddingDimension());
  }

  @Test
  void embedDocument() {
    var result =
        harrierModel.embedDocument(
            "A software engineer passionate about building AI-powered applications.");

    assertInstanceOf(Result.Success.class, result);
    var embedding = ((Result.Success<float[]>) result).value();
    assertEquals(640, embedding.length);
  }

  @Test
  void embedQuery() {
    var result = harrierModel.embedQuery("AI engineer looking for startup opportunities");

    assertInstanceOf(Result.Success.class, result);
    var embedding = ((Result.Success<float[]>) result).value();
    assertEquals(640, embedding.length);
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
  void semanticSimilarity() {
    var similar1 = "A founder building a climate tech startup focused on carbon capture.";
    var similar2 = "An entrepreneur working on environmental technology for CO2 removal.";
    var different = "A chef specializing in Italian cuisine and pasta dishes.";

    var emb1 = unwrap(harrierModel.embedDocument(similar1));
    var emb2 = unwrap(harrierModel.embedDocument(similar2));
    var emb3 = unwrap(harrierModel.embedDocument(different));

    var simSimilar = cosineSimilarity(emb1, emb2);
    var simDifferent = cosineSimilarity(emb1, emb3);

    assertTrue(
        simSimilar > simDifferent,
        "Similar texts should have higher similarity: %.4f vs %.4f"
            .formatted(simSimilar, simDifferent));
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

  @Test
  void queryDocumentMatching() {
    var profile =
        """
        Bio: Serial entrepreneur with 15 years of experience in fintech and blockchain.
        Currently building a decentralized identity platform.
        Skills: Product Management, Blockchain Development, Fundraising
        Interests: Web3, DeFi, Digital Identity, Privacy Technology
        """;

    var docEmb = unwrap(harrierModel.embedDocument(profile));
    var relevantQuery = unwrap(harrierModel.embedQuery("blockchain founder seeking investors"));
    var irrelevantQuery = unwrap(harrierModel.embedQuery("restaurant management software"));

    var simRelevant = cosineSimilarity(docEmb, relevantQuery);
    var simIrrelevant = cosineSimilarity(docEmb, irrelevantQuery);

    assertTrue(
        simRelevant > simIrrelevant,
        "Relevant query should match better: %.4f vs %.4f".formatted(simRelevant, simIrrelevant));
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
