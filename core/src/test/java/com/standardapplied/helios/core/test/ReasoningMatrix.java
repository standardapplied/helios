/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import com.standardapplied.helios.core.model.Reasoning;
import com.standardapplied.helios.core.model.Reasoning.Display;
import com.standardapplied.helios.core.model.Reasoning.Level;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

/**
 * The matrix each provider's reasoning test runs: every catalogued model against {@code
 * Reasoning.Off} and every level and display, split into the cells the provider documents as
 * accepted and those it rejects. A test writes out what each model accepts from the provider's
 * documentation, never from the catalogue under test.
 */
public final class ReasoningMatrix {

  private ReasoningMatrix() {}

  /**
   * What a model accepts, as its provider documents it.
   *
   * @param off whether {@code Reasoning.Off} is accepted
   * @param levels the accepted levels, in an enum-ordered set
   * @param displays the accepted displays, in an enum-ordered set
   */
  public record Accepts(boolean off, Set<Level> levels, Set<Display> displays) {

    /** Whether {@code reasoning} is accepted; an absent reasoning always is. */
    public boolean test(Reasoning reasoning) {
      return switch (reasoning) {
        case null -> true;
        case Reasoning.Off _ -> off;
        case Reasoning.Effort e -> levels.contains(e.level()) && displays.contains(e.display());
      };
    }

    /** How a rejection states what the model accepts. */
    public String description() {
      var effort =
          levels.isEmpty() ? "no Effort" : "Effort " + levels + " with display " + displays;
      return "it accepts " + (off ? "Off" : "no Off") + " and " + effort;
    }
  }

  /** {@code Reasoning.Off}, then every level with every display. */
  public static Stream<Reasoning> everyReasoning() {
    var efforts =
        Arrays.stream(Level.values())
            .flatMap(
                level ->
                    Arrays.stream(Display.values())
                        .map(display -> (Reasoning) new Reasoning.Effort(level, display)));
    return Stream.concat(Stream.of(new Reasoning.Off()), efforts);
  }

  /**
   * The {@code (model, reasoning)} cells of {@code documented} whose acceptance is {@code
   * accepted}.
   */
  public static <M, D> Stream<Arguments> cells(
      Map<M, D> documented, Function<D, Accepts> accepts, boolean accepted) {
    return documented.entrySet().stream()
        .flatMap(
            row ->
                everyReasoning()
                    .filter(reasoning -> accepts.apply(row.getValue()).test(reasoning) == accepted)
                    .map(reasoning -> Arguments.of(row.getKey(), reasoning)));
  }
}
