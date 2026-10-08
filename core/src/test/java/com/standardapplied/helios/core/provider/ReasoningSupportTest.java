/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.model.Reasoning;
import com.standardapplied.helios.core.model.Reasoning.Display;
import com.standardapplied.helios.core.model.Reasoning.Level;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReasoningSupportTest {

  private static final ReasoningSupport LOW_TO_HIGH_WITHOUT_OFF =
      new ReasoningSupport(
          false, EnumSet.range(Level.LOW, Level.HIGH), EnumSet.of(Display.HIDDEN, Display.SUMMARY));

  @Test
  void anAbsentReasoningIsAcceptedEvenByAModelThatTakesNothing() {
    var nothing = new ReasoningSupport(false, Set.of(), Set.of());

    assertDoesNotThrow(() -> nothing.require("m", Optional.empty()));
  }

  @Test
  void anAcceptedValuePasses() {
    var effort = new Reasoning.Effort(Level.MEDIUM, Display.SUMMARY);

    assertDoesNotThrow(() -> LOW_TO_HIGH_WITHOUT_OFF.require("m", Optional.of(effort)));
    assertDoesNotThrow(() -> ReasoningSupport.ANY.require("m", Optional.of(new Reasoning.Off())));
  }

  @Test
  void eachRejectionNamesTheModelTheRejectedValueAndEverythingAccepted() {
    var accepted =
        "; it accepts no Off and Effort [LOW, MEDIUM, HIGH] with display [HIDDEN, SUMMARY].";

    assertEquals(
        "Model m does not accept Reasoning.Off" + accepted, rejection(new Reasoning.Off()));
    assertEquals(
        "Model m does not accept Reasoning.Level.MAX" + accepted,
        rejection(new Reasoning.Effort(Level.MAX, Display.SUMMARY)));
    assertEquals(
        "Model m does not accept Reasoning.Display.PROGRESS" + accepted,
        rejection(new Reasoning.Effort(Level.LOW, Display.PROGRESS)));
  }

  @Test
  void aModelWithoutEffortSaysSo() {
    var offOnly = new ReasoningSupport(true, Set.of(), Set.of());

    var rejection =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                offOnly.require("m", Optional.of(new Reasoning.Effort(Level.LOW, Display.HIDDEN))));

    assertEquals(
        "Model m does not accept Reasoning.Level.LOW; it accepts Off and no Effort.",
        rejection.getMessage());
  }

  @Test
  void theDeclaredSetsAreCopiedAndUnmodifiable() {
    var levels = EnumSet.of(Level.LOW);
    var support = new ReasoningSupport(true, levels, Set.of(Display.HIDDEN));

    levels.add(Level.MAX);

    assertEquals(EnumSet.of(Level.LOW), support.levels());
    assertThrows(UnsupportedOperationException.class, () -> support.levels().add(Level.MAX));
    assertThrows(
        UnsupportedOperationException.class, () -> support.displays().add(Display.SUMMARY));
  }

  @Test
  void nullSetsAreRejectedByName() {
    var levels =
        assertThrows(NullPointerException.class, () -> new ReasoningSupport(true, null, Set.of()));
    var displays =
        assertThrows(NullPointerException.class, () -> new ReasoningSupport(true, Set.of(), null));

    assertEquals("levels must not be null", levels.getMessage());
    assertEquals("displays must not be null", displays.getMessage());
  }

  private static String rejection(Reasoning reasoning) {
    return assertThrows(
            IllegalArgumentException.class,
            () -> LOW_TO_HIGH_WITHOUT_OFF.require("m", Optional.of(reasoning)))
        .getMessage();
  }
}
