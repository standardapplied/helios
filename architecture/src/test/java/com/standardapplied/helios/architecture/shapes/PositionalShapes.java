/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.architecture.shapes;

import java.util.List;

/**
 * Signatures the four-in-a-row rule is proven on: each shape is flagged or passed by its parameter
 * list and visibility alone, so the bodies do nothing.
 */
public interface PositionalShapes {

  /** The 3.1.0 shape of {@code Response.Usage}: five counts in a row and no builder. */
  record PositionalUsage(
      int inputTokens,
      int outputTokens,
      int cacheCreationInputTokens,
      int cacheReadInputTokens,
      int totalTokens,
      long promptTokens) {}

  /** The same counts built by name, beside a positional factory the builder does not excuse. */
  record NamedUsage(
      int inputTokens,
      int outputTokens,
      int cacheCreationInputTokens,
      int cacheReadInputTokens,
      int totalTokens,
      long promptTokens) {

    public static Object newBuilder() {
      return new Object();
    }

    public static NamedUsage of(int input, int output, int cacheCreation, int cacheRead) {
      return new NamedUsage(input, output, cacheCreation, cacheRead, 0, 0L);
    }
  }

  /** The same counts with a builder no host can reach. */
  record HiddenBuilderUsage(
      int inputTokens,
      int outputTokens,
      int cacheCreationInputTokens,
      int cacheReadInputTokens,
      int totalTokens,
      long promptTokens) {

    static Object newBuilder() {
      return new Object();
    }
  }

  /** Four lists of one generic type in a row. */
  record SameRules(List<String> allow, List<String> ask, List<String> deny, List<String> extra) {}

  /** Four lists of one raw type whose generic types alternate. */
  record MixedRules(
      List<String> allow, List<Integer> ask, List<String> deny, List<Integer> extra) {}

  /** A run of three. */
  record ThreeInts(int first, int second, int third, long fourth) {}

  /** Four of each type, never two adjacent. */
  record Interleaved(
      int first,
      long second,
      int third,
      long fourth,
      int fifth,
      long sixth,
      int seventh,
      long eighth) {}

  /** Members of every visibility, with runs of three and four. */
  class Factories {

    protected Factories(long first, long second, long third, long fourth) {}

    public static int sum(int first, int second, int third, int fourth) {
      return first + second + third + fourth;
    }

    public String join(String first, String second, String third, String fourth) {
      return first + second + third + fourth;
    }

    static int hiddenSum(int first, int second, int third, int fourth) {
      return first + second + third + fourth;
    }

    public static int sumOfThree(int first, int second, int third) {
      return first + second + third;
    }
  }
}

/** A type no host can name, so its public record is not published API. */
final class HiddenShapes {

  private HiddenShapes() {}

  /** Four ints in a row inside a package-private type. */
  public record HiddenInts(int first, int second, int third, int fourth) {}
}
