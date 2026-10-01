/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.embedding;

import ai.singlr.core.common.Result;

/**
 * Generates vector embeddings from text. Embeddings are dense vector representations that capture
 * semantic meaning, enabling similarity search and semantic retrieval.
 */
public interface EmbeddingModel extends AutoCloseable {

  /**
   * Generate a vector embedding for the given text.
   *
   * @param text the input text to embed
   * @return the embedding vector, or failure if generation fails
   */
  Result<float[]> embed(String text);

  /**
   * Generate a vector embedding for a query (for search). Some models use different prefixes for
   * queries vs documents.
   *
   * @param query the search query to embed
   * @return the embedding vector, or failure if generation fails
   */
  default Result<float[]> embedQuery(String query) {
    return embed(query);
  }

  /**
   * Generate a vector embedding for a query, overriding the model's default query prefix /
   * instruction. Useful when a single application uses one model for multiple task shapes (e.g.
   * asymmetric retrieval and semantic similarity) that benefit from different prompts.
   *
   * <p>Implementations that don't support per-call prefixes ignore {@code customQueryPrefix} and
   * fall back to {@link #embedQuery(String)}.
   *
   * @param query the search query to embed
   * @param customQueryPrefix prefix to prepend in place of the model's default; null means use the
   *     default
   * @return the embedding vector, or failure if generation fails
   */
  default Result<float[]> embedQuery(String query, String customQueryPrefix) {
    return embedQuery(query);
  }

  /**
   * Generate a vector embedding for a document (for indexing). Some models use different prefixes
   * for documents vs queries.
   *
   * @param document the document text to embed
   * @return the embedding vector, or failure if generation fails
   */
  default Result<float[]> embedDocument(String document) {
    return embed(document);
  }

  /**
   * Generate a vector embedding for a document, overriding the model's default document prefix. See
   * {@link #embedQuery(String, String)} for the rationale.
   *
   * @param document the document text to embed
   * @param customDocumentPrefix prefix to prepend in place of the model's default; null means use
   *     the default
   * @return the embedding vector, or failure if generation fails
   */
  default Result<float[]> embedDocument(String document, String customDocumentPrefix) {
    return embedDocument(document);
  }

  /**
   * Generate vector embeddings for multiple texts in a batch.
   *
   * @param texts array of input texts to embed
   * @return array of embedding vectors, or failure if generation fails
   */
  Result<float[][]> embedBatch(String[] texts);

  /**
   * Get the dimensionality of the embedding vectors produced by this model.
   *
   * @return the embedding dimension (e.g., 768 for nomic-embed-text-v1.5)
   */
  int embeddingDimension();

  /**
   * Get the model identifier.
   *
   * @return the model name (e.g., "nomic-ai/nomic-embed-text-v1.5")
   */
  String modelName();

  /** Release resources held by this model (ONNX sessions, etc.). */
  @Override
  void close();
}
