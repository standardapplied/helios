/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.onnx;

/**
 * The arithmetic between a tokenizer and an embedding vector: truncating token arrays to a model's
 * sequence length, pooling per-token embeddings into one vector (mean pooling for encoders,
 * last-token pooling for decoders) and normalizing that vector to unit length.
 */
final class EmbeddingMath {

  private EmbeddingMath() {}

  static long[] truncate(long[] arr, int maxLength) {
    var truncated = new long[maxLength];
    System.arraycopy(arr, 0, truncated, 0, maxLength);
    return truncated;
  }

  static float[] lastTokenPooling(float[][] tokenEmbeddings, long[] attentionMask) {
    var lastTokenIndex = 0;
    for (var i = 0; i < attentionMask.length; i++) {
      if (attentionMask[i] == 1L) {
        lastTokenIndex = i;
      }
    }
    return tokenEmbeddings[lastTokenIndex].clone();
  }

  static float[] meanPooling(float[][] tokenEmbeddings, long[] attentionMask) {
    var seqLength = tokenEmbeddings.length;
    var hiddenSize = tokenEmbeddings[0].length;

    var pooled = new float[hiddenSize];
    var maskSum = new float[hiddenSize];

    for (var i = 0; i < seqLength; i++) {
      if (attentionMask[i] == 1L) {
        for (var j = 0; j < hiddenSize; j++) {
          pooled[j] += tokenEmbeddings[i][j];
          maskSum[j] += 1.0f;
        }
      }
    }

    for (var j = 0; j < hiddenSize; j++) {
      if (maskSum[j] > 0) {
        pooled[j] /= maskSum[j];
      }
    }

    return pooled;
  }

  static void normalize(float[] embedding) {
    var norm = 0.0f;
    for (var value : embedding) {
      norm += value * value;
    }
    norm = (float) Math.sqrt(norm);

    if (norm > 0) {
      for (var i = 0; i < embedding.length; i++) {
        embedding[i] /= norm;
      }
    }
  }
}
