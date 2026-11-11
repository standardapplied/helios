/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A skill holds only what the hard rules of its components accept. */
final class SkillTest {

  private static final Supplier<String> TEXT = () -> "text";

  private static Skill withFile(String path) {
    return new Skill("a", "d", "", Optional.empty(), Map.of(path, TEXT));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "a1",
        "pdf-processing",
        "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklm"
      })
  void aNameOfLowercaseGroupsJoinedByHyphensIsAccepted(String name) {
    assertEquals(name, Skill.of(name, "d", "i").name());
  }

  @ParameterizedTest
  @ValueSource(strings = {"PDF", "-a", "a-", "a--b", "a_b", ""})
  void anyOtherNameIsRefusedNamingTheRule(String name) {
    var refused = assertThrows(IllegalArgumentException.class, () -> Skill.of(name, "d", "i"));

    assertEquals(
        "skill name \"" + name + "\" must match [a-z0-9]+(-[a-z0-9]+)*", refused.getMessage());
  }

  @Test
  void aLongDescriptionAndEmptyInstructionsAreAccepted() {
    var description = "d".repeat(2_000);

    var skill = Skill.of("a", description, "");

    assertEquals(description, skill.description());
    assertEquals("", skill.instructions());
  }

  @Test
  void aBlankDescriptionIsRefused() {
    var refused = assertThrows(IllegalArgumentException.class, () -> Skill.of("a", " ", "i"));

    assertEquals("description must not be blank", refused.getMessage());
  }

  @Test
  void aBlankDirectoryIsRefused() {
    var refused =
        assertThrows(
            IllegalArgumentException.class,
            () -> new Skill("a", "d", "i", Optional.of(" "), Map.of()));

    assertEquals("directory must not be blank", refused.getMessage());
  }

  @Test
  void aNullComponentIsRefusedByName() {
    assertEquals(
        "name must not be null",
        assertThrows(NullPointerException.class, () -> Skill.of(null, "d", "i")).getMessage());
    assertEquals(
        "description must not be null",
        assertThrows(NullPointerException.class, () -> Skill.of("a", null, "i")).getMessage());
    assertEquals(
        "instructions must not be null",
        assertThrows(NullPointerException.class, () -> Skill.of("a", "d", null)).getMessage());
    assertEquals(
        "directory must not be null",
        assertThrows(NullPointerException.class, () -> new Skill("a", "d", "i", null, Map.of()))
            .getMessage());
    assertEquals(
        "files must not be null",
        assertThrows(
                NullPointerException.class, () -> new Skill("a", "d", "i", Optional.empty(), null))
            .getMessage());
  }

  @Test
  void aNullFileSupplierIsRefused() {
    var files = new HashMap<String, Supplier<String>>();
    files.put("a.md", null);

    assertThrows(
        NullPointerException.class, () -> new Skill("a", "d", "", Optional.empty(), files));
  }

  @ParameterizedTest
  @ValueSource(strings = {"a.md", "scripts/x/y.py"})
  void aRelativeFilePathIsAccepted(String path) {
    assertDoesNotThrow(() -> withFile(path));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "/a", "a//b", "./a", "a/../b", "a\\b", "a\tb"})
  void anyOtherFileKeyIsRefusedNamingTheRule(String path) {
    var refused = assertThrows(IllegalArgumentException.class, () -> withFile(path));

    assertEquals(
        "files key \""
            + path
            + "\" must be /-separated segments, none empty, . or .., with no \\ and no control"
            + " character",
        refused.getMessage());
  }

  @Test
  void theFilesAreAnImmutableCopy() {
    var files = new HashMap<String, Supplier<String>>();
    files.put("a.md", TEXT);
    var skill = new Skill("a", "d", "", Optional.of("/skills/a"), files);
    files.put("b.md", TEXT);

    assertEquals(Map.of("a.md", TEXT), skill.files());
    assertThrows(UnsupportedOperationException.class, () -> skill.files().put("c.md", TEXT));
  }

  @Test
  void ofHasNoDirectoryAndNoFiles() {
    var skill = Skill.of("a", "d", "i");

    assertEquals(new Skill("a", "d", "i", Optional.empty(), Map.of()), skill);
  }
}
