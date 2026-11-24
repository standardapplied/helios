/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

final class ToolNameSuggestionsTest {

  @Test
  void aNameThatDiffersOnlyInCaseIsSuggested() {
    assertEquals(List.of("Read"), ToolNameSuggestions.closest("read", List.of("Write", "Read")));
  }

  @Test
  void aTranspositionIsSuggested() {
    assertEquals(List.of("Read"), ToolNameSuggestions.closest("Raed", List.of("Grep", "Read")));
  }

  @Test
  void aMissingCharacterIsSuggested() {
    assertEquals(List.of("Grep"), ToolNameSuggestions.closest("Gre", List.of("Glob", "Grep")));
  }

  @Test
  void aNameBeyondTheThresholdIsNotSuggested() {
    assertEquals(List.of(), ToolNameSuggestions.closest("Bash", List.of("Read")));
  }

  @Test
  void theThresholdIsTwoUpToEightCharacters() {
    assertEquals(
        List.of("abcdefXY"),
        ToolNameSuggestions.closest("abcdefgh", List.of("abcdeXYZ", "abcdefXY")));
    assertEquals(List.of("abcd"), ToolNameSuggestions.closest("ab", List.of("abcde", "abcd")));
  }

  @Test
  void theThresholdIsAThirdOfTheCalledNameFromNineCharacters() {
    assertEquals(
        List.of("abcdefXYZ"),
        ToolNameSuggestions.closest("abcdefghi", List.of("abcdeWXYZ", "abcdefXYZ")));
  }

  @Test
  void atMostThreeAreSuggestedNearestFirstTiesInRegistryOrder() {
    assertEquals(
        List.of("TOOL", "tools", "Tool1"),
        ToolNameSuggestions.closest("tool", List.of("tool_b", "tools", "TOOL", "Tool1", "toolz")));
  }

  @Test
  void anEmptyRegistrySuggestsNothing() {
    assertEquals(List.of(), ToolNameSuggestions.closest("Read", List.of()));
  }

  @Test
  void anEmptyCalledNameSuggestsNamesOfAtMostTwoCharacters() {
    assertEquals(List.of("LS", "Go"), ToolNameSuggestions.closest("", List.of("LS", "Read", "Go")));
  }

  @Test
  void aCalledNameOver256CharactersGetsNoSuggestion() {
    var limit = "a".repeat(256);
    var over = "a".repeat(257);

    assertEquals(List.of(limit), ToolNameSuggestions.closest(limit, List.of(limit)));
    assertEquals(List.of(), ToolNameSuggestions.closest(over, List.of(over)));
  }
}
