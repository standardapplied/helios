/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A skill is built through its builder and holds only what the hard rules of its components accept.
 */
final class SkillTest {

  private static final Supplier<String> TEXT = () -> "text";
  private static final String REFUSED =
      "no &, <, >, \" and no control, format, line separator or paragraph separator character";

  private static Skill.Builder valid() {
    return Skill.newBuilder().withName("a").withDescription("d").withInstructions("i");
  }

  private static Skill withFile(String path) {
    return valid().withFile(path, TEXT).build();
  }

  @Test
  void eachSetterSetsItsOwnComponent() {
    var skill =
        Skill.newBuilder()
            .withName("pdf")
            .withDescription("Extracts text from PDFs.")
            .withInstructions("Use pdfplumber.")
            .withDirectory("/skills/pdf")
            .withFile("a.md", TEXT)
            .build();

    assertEquals(
        List.of(
            "pdf",
            "Extracts text from PDFs.",
            "Use pdfplumber.",
            Optional.of("/skills/pdf"),
            Map.of("a.md", TEXT)),
        List.of(
            skill.name(),
            skill.description(),
            skill.instructions(),
            skill.directory(),
            skill.files()));
  }

  @Test
  void aSkillWithoutDirectoryOrFilesHasNeither() {
    var skill = valid().build();

    assertEquals(Optional.empty(), skill.directory());
    assertEquals(Map.of(), skill.files());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "a1",
        "pdf-processing",
        "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijkl"
      })
  void aNameOfLowercaseGroupsJoinedByHyphensUpTo64CharactersIsAccepted(String name) {
    assertEquals(name, valid().withName(name).build().name());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "PDF",
        "-a",
        "a-",
        "a--b",
        "a_b",
        "",
        "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklm"
      })
  void anyOtherNameIsRefusedNamingTheRule(String name) {
    var refused =
        assertThrows(IllegalArgumentException.class, () -> valid().withName(name).build());

    assertEquals(
        "skill name \"" + name + "\" must match [a-z0-9]+(-[a-z0-9]+)* in at most 64 characters",
        refused.getMessage());
  }

  @Test
  void aLongDescriptionAndEmptyInstructionsAreAccepted() {
    var description = "d".repeat(2_000);

    var skill = valid().withDescription(description).withInstructions("").build();

    assertEquals(description, skill.description());
    assertEquals("", skill.instructions());
  }

  @Test
  void aBlankDescriptionOrDirectoryIsRefused() {
    assertEquals(
        "description must not be blank",
        assertThrows(IllegalArgumentException.class, () -> valid().withDescription(" ").build())
            .getMessage());
    assertEquals(
        "directory must not be blank",
        assertThrows(IllegalArgumentException.class, () -> valid().withDirectory(" ").build())
            .getMessage());
  }

  @Test
  void aMissingComponentIsRefusedByName() {
    assertEquals(
        "name must not be null",
        assertThrows(NullPointerException.class, () -> valid().withName(null).build())
            .getMessage());
    assertEquals(
        "description must not be null",
        assertThrows(NullPointerException.class, () -> valid().withDescription(null).build())
            .getMessage());
    assertEquals(
        "instructions must not be null",
        assertThrows(NullPointerException.class, () -> valid().withInstructions(null).build())
            .getMessage());
    assertEquals(
        "directory must not be null",
        assertThrows(NullPointerException.class, () -> valid().withDirectory(null)).getMessage());
    assertEquals(
        "files must not be null",
        assertThrows(NullPointerException.class, () -> valid().withFiles(null)).getMessage());
    assertEquals(
        "path must not be null",
        assertThrows(NullPointerException.class, () -> valid().withFile(null, TEXT)).getMessage());
    assertEquals(
        "content must not be null",
        assertThrows(NullPointerException.class, () -> valid().withFile("a.md", null))
            .getMessage());
  }

  @Test
  void aNullFileSupplierIsRefused() {
    var files = new HashMap<String, Supplier<String>>();
    files.put("a.md", null);

    assertThrows(NullPointerException.class, () -> valid().withFiles(files));
  }

  @ParameterizedTest
  @ValueSource(strings = {"a.md", "scripts/x/y.py", "docs/a b-ü.md"})
  void aRelativeFilePathIsAccepted(String path) {
    assertDoesNotThrow(() -> withFile(path));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "/a", "a/", "a//b", "./a", "a/../b", "a\\b"})
  void aFileKeyThatIsNoRelativePathIsRefusedNamingTheRule(String path) {
    assertRefusedFileKey(path);
  }

  /** A refused character of each class: the four XML ones, control, format, line and paragraph. */
  @ParameterizedTest
  @ValueSource(ints = {'&', '<', '>', '"', 0x09, 0x85, 0x200B, 0x2028, 0x2029})
  void aFileKeyWithARefusedCharacterIsRefusedNamingTheRule(int refused) {
    assertRefusedFileKey("a" + Character.toString(refused) + "b");
  }

  private static void assertRefusedFileKey(String path) {
    var refused = assertThrows(IllegalArgumentException.class, () -> withFile(path));

    assertEquals(
        "files key \""
            + path
            + "\" must be /-separated segments, none empty, . or .., with no \\, "
            + REFUSED,
        refused.getMessage());
  }

  @ParameterizedTest
  @ValueSource(ints = {'&', '<', '>', '"', 0x0A, 0x85, 0x200B, 0x2028, 0x2029})
  void aDirectoryWithARefusedCharacterIsRefusedNamingTheRule(int refused) {
    var directory = "/s" + Character.toString(refused) + "k";

    var thrown =
        assertThrows(
            IllegalArgumentException.class, () -> valid().withDirectory(directory).build());

    assertEquals("directory \"" + directory + "\" must have " + REFUSED, thrown.getMessage());
  }

  @Test
  void withFilesReplacesAndWithFileAppends() {
    Supplier<String> other = () -> "other";

    var skill =
        valid()
            .withFile("a.md", TEXT)
            .withFiles(Map.of("b.md", TEXT))
            .withFile("c.md", other)
            .build();

    assertEquals(Map.of("b.md", TEXT, "c.md", other), skill.files());
  }

  @Test
  void theFilesAreAnImmutableCopyTheBuilderNoLongerChanges() {
    var builder = valid().withFile("a.md", TEXT);
    var skill = builder.build();
    builder.withFile("b.md", TEXT);

    assertEquals(Map.of("a.md", TEXT), skill.files());
    assertThrows(UnsupportedOperationException.class, () -> skill.files().put("c.md", TEXT));
  }

  @Test
  void toBuilderRebuildsAnEqualSkill() {
    var bare = valid().build();
    var full = valid().withDirectory("/skills/a").withFile("a.md", TEXT).build();

    assertEquals(List.of(bare, full), List.of(bare.toBuilder().build(), full.toBuilder().build()));
    assertEquals("e", full.toBuilder().withInstructions("e").build().instructions(), "a variant");
  }
}
