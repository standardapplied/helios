/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.Result;
import com.standardapplied.helios.core.embedding.EmbeddingConfig;
import com.standardapplied.helios.core.embedding.EmbeddingModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.AfterParameterizedClassInvocation;
import org.junit.jupiter.params.BeforeParameterizedClassInvocation;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.EnumSource;

@ParameterizedClass(name = "{0}")
@EnumSource(EmbeddingModelsTest.Subject.class)
class EmbeddingModelsTest {

  enum Subject {
    EMBEDDING_GEMMA_300M(
        OnnxModelId.EMBEDDING_GEMMA_300M,
        768,
        0.5,
        "looking for a blockchain founder",
        "task: sentence similarity | query: "),
    HARRIER_OSS_V1_270M(
        OnnxModelId.HARRIER_OSS_V1_270M,
        640,
        0.5,
        "looking for a climate tech founder",
        Subject.HARRIER_INSTRUCTION),
    HARRIER_OSS_V1_0_6B(
        OnnxModelId.HARRIER_OSS_V1_0_6B,
        1024,
        0.6,
        "looking for a climate tech founder",
        Subject.HARRIER_INSTRUCTION),
    NOMIC_EMBED_V1_5(
        OnnxModelId.NOMIC_EMBED_V1_5,
        768,
        0.5,
        "looking for a climate tech founder",
        "search_document: ");

    private static final String HARRIER_INSTRUCTION =
        "Instruct: Given a brief description, find the matching member's biography\nQuery: ";

    private final OnnxModelId id;
    private final int dimension;

    /** Harrier 0.6B scores the unrelated pair at 0.56, the other models below 0.5. */
    private final double unrelatedSimilarityCeiling;

    private final String query;
    private final String customQueryPrefix;

    Subject(
        OnnxModelId id,
        int dimension,
        double unrelatedSimilarityCeiling,
        String query,
        String customQueryPrefix) {
      this.id = id;
      this.dimension = dimension;
      this.unrelatedSimilarityCeiling = unrelatedSimilarityCeiling;
      this.query = query;
      this.customQueryPrefix = customQueryPrefix;
    }
  }

  private static EmbeddingModel model;

  @Parameter private Subject subject;

  @BeforeParameterizedClassInvocation
  static void load(Subject subject) {
    model = new OnnxEmbeddingProvider().create(subject.id.id(), EmbeddingConfig.defaults());
  }

  @AfterParameterizedClassInvocation
  static void close() {
    if (model != null) {
      model.close();
    }
  }

  @Test
  void embeddingDimension() {
    assertEquals(subject.dimension, model.embeddingDimension());
  }

  @Test
  void modelName() {
    assertEquals(subject.id.id(), model.modelName());
  }

  @Test
  void embedSingleText() {
    var embedding =
        unwrap(model.embed("Interests: Technology, Healthcare, AI\nSkills: Leadership"));

    assertEquals(subject.dimension, embedding.length);
  }

  @Test
  void embedDocument() {
    var embedding =
        unwrap(
            model.embedDocument(
                "A software engineer passionate about building AI-powered applications."));

    assertEquals(subject.dimension, embedding.length);
  }

  @Test
  void embedQuery() {
    var embedding = unwrap(model.embedQuery("AI engineer looking for startup opportunities"));

    assertEquals(subject.dimension, embedding.length);
  }

  @Test
  void embedEmptyTextReturnsFailure() {
    assertInstanceOf(Result.Failure.class, model.embed(""));
  }

  @Test
  void embedNullTextReturnsFailure() {
    assertInstanceOf(Result.Failure.class, model.embed(null));
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
      assertEquals(subject.dimension, embedding.length);
    }
  }

  @Test
  void embedBatchNullArrayReturnsFailure() {
    assertInstanceOf(Result.Failure.class, model.embedBatch(null));
  }

  @Test
  void embedBatchEmptyArrayReturnsFailure() {
    assertInstanceOf(Result.Failure.class, model.embedBatch(new String[0]));
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
  void identicalTextsProduceSameEmbeddings() {
    var emb1 = unwrap(model.embed("A man is eating food."));
    var emb2 = unwrap(model.embed("A man is eating food."));

    assertEquals(1.0f, cosineSimilarity(emb1, emb2), 0.0001f);
  }

  @Test
  void embeddingIsNormalized() {
    assertEquals(1.0f, norm(unwrap(model.embedDocument("This is a test document."))), 0.0001f);
    assertEquals(1.0f, norm(unwrap(model.embed("This is a test sentence."))), 0.0001f);
  }

  @Test
  void semanticSimilarity() {
    var similar1 = "A founder building a climate tech startup focused on carbon capture.";
    var similar2 = "An entrepreneur working on environmental technology for CO2 removal.";
    var different = "A chef specializing in Italian cuisine and pasta dishes.";

    var emb1 = unwrap(model.embedDocument(similar1));
    var emb2 = unwrap(model.embedDocument(similar2));
    var emb3 = unwrap(model.embedDocument(different));

    var simSimilar = cosineSimilarity(emb1, emb2);
    var simDifferent = cosineSimilarity(emb1, emb3);

    assertTrue(
        simSimilar > simDifferent,
        "Similar texts should have higher similarity: %.4f vs %.4f"
            .formatted(simSimilar, simDifferent));
    assertTrue(simSimilar > 0.6, "Similar texts should have similarity > 0.6");
    assertTrue(
        simDifferent < subject.unrelatedSimilarityCeiling,
        "Different texts should have similarity < %.1f, was %.4f"
            .formatted(subject.unrelatedSimilarityCeiling, simDifferent));
  }

  @Test
  void semanticSimilarityOfPlainText() {
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
  void customQueryPrefixOverridesDefault() {
    var defaultEmb = unwrap(model.embedQuery(subject.query));
    var customEmb = unwrap(model.embedQuery(subject.query, subject.customQueryPrefix));

    var sim = cosineSimilarity(defaultEmb, customEmb);
    assertTrue(
        sim < 0.999f,
        "Custom prefix should produce a measurably different embedding (cosine=%.4f)"
            .formatted(sim));
  }

  @Test
  void customDocumentPrefixOverridesDefault() {
    var doc = "A founder building decentralized identity infrastructure.";
    var defaultEmb = unwrap(model.embedDocument(doc));
    var customEmb = unwrap(model.embedDocument(doc, "title: profile | text: "));

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

    var docEmb = unwrap(model.embedDocument(profile));
    var relevantQuery = unwrap(model.embedQuery("blockchain founder seeking investors"));
    var irrelevantQuery = unwrap(model.embedQuery("restaurant management software"));

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

  private static float norm(float[] embedding) {
    var sumOfSquares = 0.0f;
    for (var value : embedding) {
      sumOfSquares += value * value;
    }
    return (float) Math.sqrt(sumOfSquares);
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
