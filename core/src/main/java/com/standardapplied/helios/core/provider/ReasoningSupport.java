/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import com.standardapplied.helios.core.model.Reasoning;
import com.standardapplied.helios.core.model.Reasoning.Display;
import com.standardapplied.helios.core.model.Reasoning.Level;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The {@link Reasoning} values a model accepts, as its provider's catalogue declares them, and the
 * check that rejects any other value when the model is created. A rejection names the model, the
 * rejected value and everything the model accepts.
 *
 * @param off whether {@link Reasoning.Off} is accepted
 * @param levels the accepted {@link Reasoning.Effort} levels, in ascending order; empty when the
 *     model takes no effort at all
 * @param displays the accepted {@link Reasoning.Effort} displays, in declaration order
 */
public record ReasoningSupport(boolean off, Set<Level> levels, Set<Display> displays) {

  /** Every value: what a model the catalogue does not know accepts, the API being the judge. */
  public static final ReasoningSupport ANY =
      new ReasoningSupport(true, EnumSet.allOf(Level.class), EnumSet.allOf(Display.class));

  /**
   * @throws NullPointerException if {@code levels} or {@code displays} is null
   */
  public ReasoningSupport {
    Objects.requireNonNull(levels, "levels must not be null");
    Objects.requireNonNull(displays, "displays must not be null");
    levels = Collections.unmodifiableSet(copy(levels, Level.class));
    displays = Collections.unmodifiableSet(copy(displays, Display.class));
  }

  /**
   * Rejects {@code reasoning} unless this support accepts it. An absent reasoning is always
   * accepted: nothing is sent for it.
   *
   * @param modelId the model the reasoning was configured for, named in the rejection
   * @param reasoning the configured reasoning
   * @throws IllegalArgumentException naming the model, the rejected value and the accepted values
   */
  public void require(String modelId, Optional<Reasoning> reasoning) {
    switch (reasoning.orElse(null)) {
      case null -> {}
      case Reasoning.Off _ when !off -> throw rejection(modelId, "Reasoning.Off");
      case Reasoning.Off _ -> {}
      case Reasoning.Effort e when !levels.contains(e.level()) ->
          throw rejection(modelId, "Reasoning.Level." + e.level());
      case Reasoning.Effort e when !displays.contains(e.display()) ->
          throw rejection(modelId, "Reasoning.Display." + e.display());
      case Reasoning.Effort _ -> {}
    }
  }

  private IllegalArgumentException rejection(String modelId, String rejected) {
    var effort = levels.isEmpty() ? "no Effort" : "Effort " + levels + " with display " + displays;
    return new IllegalArgumentException(
        "Model "
            + modelId
            + " does not accept "
            + rejected
            + "; it accepts "
            + (off ? "Off" : "no Off")
            + " and "
            + effort
            + ".");
  }

  private static <E extends Enum<E>> EnumSet<E> copy(Collection<E> values, Class<E> type) {
    var copy = EnumSet.noneOf(type);
    copy.addAll(values);
    return copy;
  }
}
