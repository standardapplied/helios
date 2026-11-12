/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.skills;

import static com.standardapplied.helios.skills.SkillFolders.directory;
import static com.standardapplied.helios.skills.SkillFolders.link;
import static com.standardapplied.helios.skills.SkillFolders.load;
import static com.standardapplied.helios.skills.SkillFolders.nameAndDescription;
import static com.standardapplied.helios.skills.SkillFolders.real;
import static com.standardapplied.helios.skills.SkillFolders.skill;
import static com.standardapplied.helios.skills.SkillFolders.skillFile;
import static com.standardapplied.helios.skills.SkillFolders.unreadable;
import static com.standardapplied.helios.skills.SkillFolders.write;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.session.Skill;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How one {@code SKILL.md} is read, leniently where {@code Skill} allows it, and how a skill's
 * bundled files are listed and read at load into a snapshot that never reads the disk again.
 */
final class SkillReaderTest {

  private static final String BAD_NAME =
      "must match [a-z0-9]+(-[a-z0-9]+)* in at most 64 characters";
  private static final String NOT_A_STRING = " is missing or not a string";
  private static final String NOT_YAML = "its frontmatter is not valid YAML: ";
  private static final String USE_AT_ITS_PATH = "; use it at its path under the skill directory";
  private static final String TOO_LARGE = ": it is larger than 60000 bytes" + USE_AT_ITS_PATH;
  private static final String NOT_UTF8 = ": it is not UTF-8" + USE_AT_ITS_PATH;
  private static final String REFUSED_KEY =
      "\" must be /-separated segments, none empty, . or .., with no \\\\, no <, >, \" and no"
          + " control, format, line separator or paragraph separator character";

  @TempDir Path temp;

  private Path root() {
    return directory(temp.resolve("root"));
  }

  /** {@code root/pdf/SKILL.md} holding {@code content}. */
  private Path pdf(String content) {
    return write(root().resolve("pdf/SKILL.md"), content);
  }

  private Path pdf(byte[] content) {
    return write(root().resolve("pdf/SKILL.md"), content);
  }

  /** Loading {@code root/pdf} skips it with exactly this reason. */
  private void assertSkipped(String reason) {
    var scanned = load(root());

    assertEquals(List.of(), scanned.skills());
    assertEquals(
        List.of("Skipping skill " + real(root()).resolve("pdf/SKILL.md") + ": " + reason),
        scanned.warnings());
  }

  /** Loading {@code root/pdf} skips it with a reason that starts with {@code start}. */
  private void assertSkippedStarting(String start) {
    var scanned = load(root());
    var prefix = "Skipping skill " + real(root()).resolve("pdf/SKILL.md") + ": " + start;

    assertEquals(List.of(), scanned.skills());
    assertEquals(1, scanned.warnings().size(), scanned.warnings()::toString);
    assertTrue(scanned.warnings().getFirst().startsWith(prefix), scanned.warnings()::toString);
  }

  private static String read(Skill skill, String key) {
    return skill.files().get(key).get();
  }

  private static void assertRefused(Skill skill, String key, String reason) {
    var refused = assertThrows(UncheckedIOException.class, () -> read(skill, key));

    assertEquals("skill file " + key + reason, refused.getMessage());
  }

  /** What each file of {@code skill} reads now: its text, or the message it throws. */
  private static Map<String, String> outcomes(Skill skill) {
    var outcomes = new TreeMap<String, String>();
    skill.files().forEach((key, content) -> outcomes.put(key, outcome(content)));
    return outcomes;
  }

  private static String outcome(Supplier<String> content) {
    try {
      return content.get();
    } catch (UncheckedIOException e) {
      return "threw " + e.getMessage();
    }
  }

  private String scanning(String what) {
    return "Scanning " + real(root()) + " for skills: " + what;
  }

  @Test
  void aNameUnlikeItsDirectorysLoadsWithAWarning() {
    write(root().resolve("pdf-tools/SKILL.md"), skillFile(nameAndDescription("pdf"), ""));

    var scanned = load(root());

    assertEquals(List.of("pdf"), scanned.names());
    assertEquals(
        List.of(
            "Skill pdf at "
                + real(root()).resolve("pdf-tools")
                + ": its name differs from its directory's name; loading it"),
        scanned.warnings());
  }

  @Test
  void aDescriptionOf1024CharactersLoadsSilently() {
    pdf(skillFile("name: pdf\ndescription: " + "d".repeat(1_024), ""));

    var scanned = load(root());

    assertEquals(List.of("pdf"), scanned.names());
    assertEquals(List.of(), scanned.warnings());
  }

  @Test
  void aDescriptionOver1024CharactersIsSkippedWithSkillsMessage() {
    pdf(skillFile("name: pdf\ndescription: " + "d".repeat(1_025), ""));

    assertSkipped("description must be at most 1024 characters, not 1025");
  }

  @Test
  void aMissingDescriptionIsSkipped() {
    pdf(skillFile("name: pdf", ""));

    assertSkipped("its frontmatter's description" + NOT_A_STRING);
  }

  @Test
  void aNumericDescriptionIsSkipped() {
    pdf(skillFile("name: pdf\ndescription: 42", ""));

    assertSkipped("its frontmatter's description" + NOT_A_STRING);
  }

  @Test
  void aBlankDescriptionIsSkippedWithSkillsMessage() {
    pdf(skillFile("name: pdf\ndescription: ' '", ""));

    assertSkipped("description must not be blank");
  }

  @Test
  void aMissingNameIsSkipped() {
    pdf(skillFile("description: Does pdf.", ""));

    assertSkipped("its frontmatter's name" + NOT_A_STRING);
  }

  @Test
  void aNumericNameIsSkipped() {
    pdf(skillFile("name: 42\ndescription: Does pdf.", ""));

    assertSkipped("its frontmatter's name" + NOT_A_STRING);
  }

  @Test
  void anUppercaseNameIsSkippedWithSkillsMessage() {
    pdf(skillFile("name: PDF\ndescription: Does pdf.", ""));

    assertSkipped("skill name \"PDF\" " + BAD_NAME);
  }

  @Test
  void aNameOf65CharactersIsSkippedWithSkillsMessage() {
    var name = "a".repeat(65);
    pdf(skillFile("name: " + name + "\ndescription: Does pdf.", ""));

    assertSkipped("skill name \"" + name + "\" " + BAD_NAME);
  }

  @Test
  void aDirectoryWithARefusedCharacterIsSkippedWithSkillsMessage() {
    var file = write(root().resolve("a\"b/SKILL.md"), skillFile(nameAndDescription("ab"), ""));
    var directory = real(file.getParent());

    var scanned = load(root());

    assertEquals(List.of(), scanned.skills());
    assertEquals(
        List.of(
            "Skipping skill "
                + directory.resolve("SKILL.md")
                + ": directory \""
                + directory
                + "\" must have no <, >, \" and no control, format, line separator or paragraph"
                + " separator character"),
        scanned.warnings());
  }

  @Test
  void aDirectoryWithAnAmpersandLoads() {
    var directory = skill(root().resolve("R&D"), "pdf");

    assertEquals(Optional.of(real(directory).toString()), load(root()).only().directory());
  }

  @Test
  void aNameFromDiskWithALineBreakIsOneWarningOnOneLine() {
    var directory = skill(root(), "pdf");
    write(directory.resolve("a\nb.md"), "x");

    var scanned = load(root());

    assertEquals(Set.of(), scanned.only().files().keySet());
    assertEquals(
        List.of(
            "Skill pdf: leaving out its file a\\u{a}b.md: files key \"a\\u{a}b.md" + REFUSED_KEY),
        scanned.warnings());
    assertFalse(scanned.warnings().getFirst().contains("\n"));
  }

  @Test
  void anUnquotedColonInTheDescriptionIsSkippedAsInvalidYaml() {
    pdf(skillFile("name: pdf\ndescription: Use when: you have a PDF", ""));

    assertSkippedStarting(NOT_YAML);
  }

  @Test
  void anUnclosedFlowSequenceIsSkippedAsInvalidYaml() {
    pdf(skillFile("name: [unclosed\ndescription: Does pdf.", ""));

    assertSkippedStarting(NOT_YAML);
  }

  @Test
  void aDuplicatedKeyIsSkippedAsInvalidYaml() {
    pdf(skillFile("name: pdf\nname: other\ndescription: Does pdf.", ""));

    assertSkippedStarting(NOT_YAML + "Duplicate Object property \"name\"");
  }

  @Test
  void anAliasIsSkippedRatherThanReadAsItsAnchorsName() {
    pdf(skillFile("name: &d pdf\ndescription: *d", ""));

    assertSkipped("its frontmatter uses a YAML alias, which skills do not support");
  }

  @Test
  void aFrontmatterListIsSkipped() {
    pdf(skillFile("- name\n- description", ""));

    assertSkipped("its frontmatter is not a mapping");
  }

  @Test
  void anEmptyFrontmatterIsSkipped() {
    pdf("---\n---\nbody");

    assertSkipped("its frontmatter is not a mapping");
  }

  @Test
  void noFrontmatterIsSkipped() {
    pdf("# PDF\n---\nname: pdf\n---\n");

    assertSkipped("it does not start with a --- line");
  }

  @Test
  void aFileWithoutAnyFenceIsSkipped() {
    pdf("# PDF\nExtracts text.");

    assertSkipped("it does not start with a --- line");
  }

  @Test
  void noClosingLineIsSkipped() {
    pdf("---\nname: pdf\ndescription: Does pdf.\n----\nbody");

    assertSkipped("its frontmatter has no closing --- line");
  }

  @Test
  void aFileOverOneMebibyteIsSkipped() {
    var head = skillFile(nameAndDescription("pdf"), "");
    pdf(head + "b".repeat(SkillReader.MAX_SKILL_FILE_BYTES + 1 - head.length()));

    assertSkipped("it is larger than 1048576 bytes");
  }

  @Test
  void aFileOfExactlyOneMebibyteLoads() {
    var head = skillFile(nameAndDescription("pdf"), "");
    pdf(head + "b".repeat(SkillReader.MAX_SKILL_FILE_BYTES - head.length()));

    assertEquals(List.of("pdf"), load(root()).names());
  }

  @Test
  void invalidUtf8IsSkipped() {
    var text = skillFile(nameAndDescription("pdf"), "caf").getBytes(UTF_8);
    var bytes = new byte[text.length + 1];
    System.arraycopy(text, 0, bytes, 0, text.length);
    bytes[text.length] = (byte) 0xE9;
    pdf(bytes);

    assertSkipped("it is not UTF-8");
  }

  @Test
  void anUnreadableSkillMdIsSkipped() throws Exception {
    var file = pdf(skillFile(nameAndDescription("pdf"), ""));

    try (var ignored = unreadable(file)) {
      assertSkipped("reading it failed: java.nio.file.AccessDeniedException: " + real(file));
    }
  }

  @Test
  void anUnreadableFolderInASkillIsLeftOutWithOneWarningAndTheSkillLoads() throws Exception {
    var directory = skill(root(), "pdf");
    write(directory.resolve("ok.md"), "ok");
    var locked = write(directory.resolve("locked/secret.md"), "x").getParent();

    try (var ignored = unreadable(locked)) {
      var scanned = load(root());

      var lockedPath = real(directory).resolve("locked");
      assertEquals(Set.of("ok.md"), scanned.only().files().keySet());
      assertEquals(
          List.of(
              scanning(
                  "left out "
                      + lockedPath
                      + " (reading it failed: java.nio.file.AccessDeniedException: "
                      + lockedPath
                      + ")")),
          scanned.warnings());
    }
  }

  @Test
  void anUnreadableFolderBelowTheDeepestListedLevelIsIgnoredSilently() throws Exception {
    var directory = skill(root(), "pdf");
    write(directory.resolve("a/b/c/d.md"), "depth four");
    var tooDeep = write(directory.resolve("a/b/c/d/e.md"), "depth five").getParent();

    try (var ignored = unreadable(tooDeep)) {
      var scanned = load(root());

      assertEquals(Set.of("a/b/c/d.md"), scanned.only().files().keySet());
      assertEquals(List.of(), scanned.warnings());
    }
  }

  @Test
  void anUnreadableHiddenFolderInASkillIsIgnoredSilently() throws Exception {
    var directory = skill(root(), "pdf");
    var hidden = write(directory.resolve(".git/config"), "x").getParent();

    try (var ignored = unreadable(hidden)) {
      var scanned = load(root());

      assertEquals(List.of("pdf"), scanned.names());
      assertEquals(List.of(), scanned.warnings());
    }
  }

  @Test
  void aByteOrderMarkAndCrlfLineEndsLoadSilently() {
    pdf(
        Character.toString(0xFEFF)
            + "---\r\nname: pdf\r\ndescription: Does pdf.\r\n---\r\nStep one.\r\nStep two.\r\n");

    var scanned = load(root());

    assertEquals("Step one.\nStep two.", scanned.only().instructions());
    assertEquals("Does pdf.", scanned.only().description());
    assertEquals(List.of(), scanned.warnings());
  }

  @Test
  void unknownKeysAndAllowedToolsLoadSilently() {
    pdf(
        skillFile(
            nameAndDescription("pdf")
                + "\nlicense: Apache-2.0\ncompatibility: Requires python3\n"
                + "metadata:\n  author: someone\n  version: \"1.0\"\n"
                + "allowed-tools: Bash(git:*) Read\nx-custom: [1, 2]",
            ""));

    var scanned = load(root());

    assertEquals(List.of("pdf"), scanned.names());
    assertEquals(List.of(), scanned.warnings());
  }

  @Test
  void fencesMayCarryTrailingBlanks() {
    pdf("--- \t\n" + nameAndDescription("pdf") + "\n---  \nbody");

    assertEquals("body", load(root()).only().instructions());
  }

  @Test
  void theInstructionsAreTheStrippedBodyAndTheDirectoryTheRealPath() {
    var root = root();
    write(
        root.resolve("pdf/SKILL.md"),
        skillFile(
            nameAndDescription("pdf"), "\n\n  Use it." + Character.toString(0x2003) + "\n\n"));
    var linked = link(temp.resolve("linked"), root);

    var skill = load(linked).only();

    assertEquals(
        List.of("pdf", "Does pdf.", "Use it.", Optional.of(real(root).resolve("pdf").toString())),
        List.of(skill.name(), skill.description(), skill.instructions(), skill.directory()));
  }

  @Test
  void theListingHoldsRegularFilesToDepthFourButTheTopSkillMdHiddenNodeModulesAndLinks() {
    var directory = skill(root(), "pdf");
    write(directory.resolve("scripts/a.py"), "print(1)");
    write(directory.resolve("a/b/c/d.md"), "depth four");
    write(directory.resolve("a/b/c/d/e.md"), "depth five");
    write(directory.resolve(".env"), "SECRET=1");
    write(directory.resolve(".git/config"), "x");
    write(directory.resolve("node_modules/m.js"), "x");
    write(directory.resolve("node_modules.md"), "kept");
    link(directory.resolve("link.md"), directory.resolve("scripts/a.py"));
    link(directory.resolve("linked"), directory.resolve("scripts"));

    var scanned = load(root());

    assertEquals(
        Set.of("scripts/a.py", "a/b/c/d.md", "node_modules.md"), scanned.only().files().keySet());
    assertEquals(List.of(), scanned.warnings());
  }

  @Test
  void a1001stFileIsLeftOutWithAWarning() {
    var directory = skill(root(), "pdf");
    IntStream.range(0, SkillWalk.MAX_FILES + 2)
        .forEach(i -> write(directory.resolve("f/" + i + ".md"), ""));

    var scanned = load(root());

    assertEquals(SkillWalk.MAX_FILES, scanned.only().files().size());
    assertEquals(
        List.of(scanning("listed 1000 files of " + real(directory) + "; the rest are left out")),
        scanned.warnings());
  }

  @Test
  void directoriesPast2000AreLeftOutWithAWarning() {
    var directory = skill(root(), "pdf");
    IntStream.range(0, SkillWalk.MAX_DIRECTORIES)
        .forEach(i -> directory(directory.resolve("d" + i)));

    var scanned = load(root());

    assertEquals(List.of("pdf"), scanned.names());
    assertEquals(
        List.of(scanning("stopped after 2000 directories; the rest are left out")),
        scanned.warnings());
  }

  @Test
  void aPathSkillRefusesIsLeftOutWithItsMessageAndTheSkillLoads() {
    var directory = skill(root(), "pdf");
    write(directory.resolve("a<b.md"), "x");
    write(directory.resolve("a\\b.md"), "x");
    write(directory.resolve("ok.md"), "ok");

    var scanned = load(root());

    assertEquals(Set.of("ok.md"), scanned.only().files().keySet());
    assertEquals(
        List.of("a<b.md", "a\\\\b.md").stream()
            .map(
                key ->
                    "Skill pdf: leaving out its file " + key + ": files key \"" + key + REFUSED_KEY)
            .toList(),
        scanned.warnings().stream().sorted().toList());
  }

  @Test
  void aFileCreatedAfterLoadIsNotListed() {
    var directory = skill(root(), "pdf");
    var skill = load(root()).only();

    write(directory.resolve("later.md"), "later");

    assertEquals(Set.of(), skill.files().keySet());
  }

  @Test
  void aFileOfExactly60000BytesReadsWhole() {
    var directory = skill(root(), "pdf");
    var text = "é".repeat(30_000);
    write(directory.resolve("big.md"), text);

    assertEquals(text, read(load(root()).only(), "big.md"));
  }

  @Test
  void aFileOf60001BytesIsRefused() {
    var directory = skill(root(), "pdf");
    write(directory.resolve("big.md"), "b".repeat(60_001));

    assertRefused(load(root()).only(), "big.md", TOO_LARGE);
    assertEquals(60_000, SkillReader.MAX_BUNDLED_FILE_BYTES);
  }

  @Test
  void aFileThatIsNotUtf8IsRefused() {
    var directory = skill(root(), "pdf");
    write(directory.resolve("image.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G'});

    assertRefused(load(root()).only(), "image.png", NOT_UTF8);
  }

  @Test
  void anUnreadableFileIsLeftOutWithAWarningAndTheSkillLoads() throws Exception {
    var directory = skill(root(), "pdf");
    var file = write(directory.resolve("a.md"), "a");
    write(directory.resolve("b.md"), "b");

    try (var ignored = unreadable(file)) {
      var scanned = load(root());

      assertEquals(Set.of("b.md"), scanned.only().files().keySet());
      assertEquals(
          List.of(
              "Skill pdf: leaving out its file a.md: reading it failed:"
                  + " java.nio.file.AccessDeniedException: "
                  + real(file)),
          scanned.warnings());
    }
  }

  @Test
  void aLinkInASkillIsNeitherListedNorReadWhereverItLeads() {
    var directory = skill(root(), "pdf");
    write(directory.resolve("scripts/a.py"), "print(1)");
    var outside = write(temp.resolve("secret.txt"), "secret");
    link(directory.resolve("inside.md"), directory.resolve("scripts/a.py"));
    link(directory.resolve("outside.md"), outside);
    link(directory.resolve("refs"), outside.getParent());

    var scanned = load(root());

    assertEquals(Map.of("scripts/a.py", "print(1)"), outcomes(scanned.only()));
    assertEquals(List.of(), scanned.warnings());
  }

  /** A skill whose files are text, too large and not UTF-8, loaded, with what each reads. */
  private Skill loadedSkill() {
    var directory = skill(root(), "pdf");
    write(directory.resolve("a.md"), "a");
    write(directory.resolve("refs/b.md"), "b");
    write(directory.resolve("big.md"), "b".repeat(60_001));
    write(directory.resolve("image.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G'});
    var skill = load(root()).only();
    assertEquals(
        Map.of(
            "a.md",
            "a",
            "refs/b.md",
            "b",
            "big.md",
            "threw skill file big.md" + TOO_LARGE,
            "image.png",
            "threw skill file image.png" + NOT_UTF8),
        outcomes(skill));
    return skill;
  }

  @Test
  void nothingIsReadAfterTheCallSoDeletingTheFolderChangesNoRead() throws IOException {
    var skill = loadedSkill();
    var before = outcomes(skill);

    try (var tree = Files.walk(root())) {
      tree.sorted(Comparator.reverseOrder()).forEach(SkillFolders::delete);
    }

    assertEquals(before, outcomes(skill));
  }

  @Test
  void aChangeAfterTheCallIsNotSeenUntilTheNextLoad() {
    var directory = skill(root(), "pdf");
    var file = write(directory.resolve("R&D/guide.md"), "old");
    var skill = load(root()).only();

    write(file, "new");

    assertEquals("old", read(skill, "R&D/guide.md"));
    assertEquals("new", read(load(root()).only(), "R&D/guide.md"));
  }

  @Test
  void theReasonAFileCannotBeReadIsFixedAtLoad() {
    var skill = loadedSkill();
    var before = outcomes(skill);

    write(root().resolve("pdf/big.md"), "short now");
    write(root().resolve("pdf/image.png"), "text now");

    assertEquals(before, outcomes(skill));
  }

  @Test
  void aBoundedReadAsksForAtMostOneByteMoreThanItsCap() throws IOException {
    assertEquals(11, SkillReader.bytes(endlessUpTo(11), 10).length);
  }

  /** An endless stream of {@code a} that fails the test when asked for more than {@code most}. */
  private static InputStream endlessUpTo(long most) {
    return new InputStream() {
      private long asked;

      @Override
      public int read() {
        return read(new byte[1], 0, 1) < 0 ? -1 : 'a';
      }

      @Override
      public int read(byte[] b, int off, int len) {
        asked += len;
        if (asked > most) {
          throw new AssertionError("asked for " + asked + " bytes, more than " + most);
        }
        Arrays.fill(b, off, off + len, (byte) 'a');
        return len;
      }
    };
  }
}
