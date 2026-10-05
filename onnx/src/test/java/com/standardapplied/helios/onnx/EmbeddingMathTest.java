/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.onnx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import org.junit.jupiter.api.Test;

class EmbeddingMathTest {

  @Test
  void truncateKeepsTheLeadingTokens() {
    assertArrayEquals(new long[] {7, 8}, EmbeddingMath.truncate(new long[] {7, 8, 9, 10}, 2));
  }

  @Test
  void lastTokenPoolingTakesACopyOfTheLastAttendedToken() {
    var tokens = new float[][] {{1f, 2f}, {3f, 4f}, {5f, 6f}};

    var pooled = EmbeddingMath.lastTokenPooling(tokens, new long[] {1, 1, 0});

    assertArrayEquals(new float[] {3f, 4f}, pooled);
    assertNotSame(tokens[1], pooled);
  }

  @Test
  void lastTokenPoolingWithNothingAttendedTakesTheFirstToken() {
    var tokens = new float[][] {{1f, 2f}, {3f, 4f}};

    assertArrayEquals(
        new float[] {1f, 2f}, EmbeddingMath.lastTokenPooling(tokens, new long[] {0, 0}));
  }

  @Test
  void meanPoolingAveragesTheAttendedTokensOnly() {
    var tokens = new float[][] {{1f, 2f}, {100f, 100f}, {3f, 4f}};

    assertArrayEquals(
        new float[] {2f, 3f}, EmbeddingMath.meanPooling(tokens, new long[] {1, 0, 1}));
  }

  @Test
  void meanPoolingWithNothingAttendedIsTheZeroVector() {
    var tokens = new float[][] {{1f, 2f}, {3f, 4f}};

    assertArrayEquals(new float[] {0f, 0f}, EmbeddingMath.meanPooling(tokens, new long[] {0, 0}));
  }

  @Test
  void normalizeScalesToUnitLengthInPlace() {
    var embedding = new float[] {3f, 4f};

    EmbeddingMath.normalize(embedding);

    assertArrayEquals(new float[] {0.6f, 0.8f}, embedding);
  }

  @Test
  void normalizeLeavesTheZeroVectorAlone() {
    var embedding = new float[] {0f, 0f};

    EmbeddingMath.normalize(embedding);

    assertArrayEquals(new float[] {0f, 0f}, embedding);
  }
}
