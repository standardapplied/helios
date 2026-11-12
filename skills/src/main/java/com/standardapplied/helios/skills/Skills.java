/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.skills;

import com.standardapplied.helios.session.Skill;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Reads Agent Skills folders (agentskills.io) into {@link Skill} values for {@code
 * SessionOptions.Builder.withSkills}.
 *
 * <p>Every directory up to four levels below a folder that holds a {@code SKILL.md} regular file is
 * a skill, the folder itself included. Its {@code SKILL.md}, at most 1 MiB, is YAML frontmatter
 * between {@code ---} lines, of which {@code name} and {@code description} are read, then the body,
 * its instructions. Its other regular files, to four levels and at most 1,000, are its files; a
 * {@code SKILL.md} inside a skill is one of its files, not a second skill. Below a folder no link
 * is followed, hidden entries and {@code node_modules} are skipped, and at most 2,000 directories
 * and 10,000 files are read.
 *
 * <p>A loaded skill is a snapshot: a call reads everything once, and nothing touches the disk after
 * it returns. A file of at most 60,000 bytes of UTF-8 is kept as text; a larger or binary one is
 * listed, for a script to use at its path, and reading it fails. One call keeps at most 16 MiB of
 * file bytes, which may take about twice that in heap. A change on disk is seen by calling again.
 *
 * <p>Load skills once, at startup or while no session can write their folders, and reuse the list
 * across sessions: skills are immutable values. A process rewriting a folder during the call can
 * make the loader serve any file the JVM can read as a skill's file, or block the call forever;
 * call it from a platform thread, not a virtual one. After the call returns, nothing on disk can
 * affect a loaded skill. Which folders to load is the host's trust decision: a skill's instructions
 * reach the model, and its scripts may be proposed to {@code Execute}.
 *
 * <p>Reading is lenient: a skill or file that cannot be loaded, including one whose name,
 * description, directory or file path {@code Skill} refuses, is left out and the rest load. Each
 * problem, and each departure from the standard that still loads, is a {@code WARNING} on the
 * logger {@code com.standardapplied.helios.skills.Skills}, the only report. When two skills share a
 * name, the first found wins. Results are in folder order, then by directory path.
 */
public final class Skills {

  private Skills() {}

  /**
   * Read the skills of the user and of a project, in this order: {@code ~/.agents/skills}, {@code
   * ~/.claude/skills}, {@code <projectRoot>/.agents/skills} and {@code
   * <projectRoot>/.claude/skills}, so a user skill wins over a project skill of the same name and a
   * repository cannot take the place of a skill the user installed. A folder that is not a
   * directory contributes nothing. A project folder whose real path leads outside the project is
   * skipped with a warning; a user folder may be a link. {@code ~} is the JVM's {@code user.home},
   * read on each call.
   *
   * @param projectRoot the project's directory
   * @return the skills found, unmodifiable
   * @throws IllegalArgumentException if {@code projectRoot} is not an existing directory
   * @throws NullPointerException if {@code projectRoot} is null
   */
  public static List<Skill> discover(Path projectRoot) {
    return discover(projectRoot, Path.of(System.getProperty("user.home")));
  }

  static List<Skill> discover(Path projectRoot, Path home) {
    var project = requireDirectory("project root", projectRoot);
    return scan(
        Stream.of(
                realDirectory(home.resolve(".agents/skills")),
                realDirectory(home.resolve(".claude/skills")),
                inProject(project, ".agents/skills"),
                inProject(project, ".claude/skills"))
            .flatMap(Optional::stream)
            .toList());
  }

  /**
   * Read the skills below the given folders, in order, so a skill under an earlier folder wins over
   * one of the same name under a later one. A folder is the host's choice: it is resolved to its
   * real path, a link to it followed, and may itself be a skill's directory.
   *
   * @param roots the folders to scan; none gives no skills
   * @return the skills found, unmodifiable
   * @throws IllegalArgumentException if a folder is not an existing directory
   * @throws NullPointerException if {@code roots} or a folder is null
   */
  public static List<Skill> load(Path... roots) {
    return scan(Arrays.stream(roots).map(root -> requireDirectory("skills root", root)).toList());
  }

  private static Path requireDirectory(String what, Path path) {
    Objects.requireNonNull(path, () -> what + " must not be null");
    return realDirectory(path)
        .orElseThrow(
            () ->
                new IllegalArgumentException(what + " " + path + " is not an existing directory"));
  }

  private static Optional<Path> realDirectory(Path path) {
    try {
      var real = path.toRealPath();
      return Files.isDirectory(real) ? Optional.of(real) : Optional.empty();
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  /**
   * The project's {@code folder}, while its real path, links resolved, stays inside the project.
   */
  private static Optional<Path> inProject(Path project, String folder) {
    var path = project.resolve(folder);
    var real = realDirectory(path);
    if (real.isPresent() && !real.get().startsWith(project)) {
      SkillLog.warn(
          "Skipping %s: it leads through a link to %s, outside the project %s; pass that folder to"
              + " Skills.load if you trust it",
          path, real.get(), project);
      return Optional.empty();
    }
    return real;
  }

  private static List<Skill> scan(List<Path> roots) {
    var skillFiles = new LinkedHashMap<Path, List<String>>();
    roots.stream()
        .distinct()
        .forEach(root -> SkillWalk.walk(root).forEach(skillFiles::putIfAbsent));
    var reader = new SkillReader();
    var skills = new LinkedHashMap<String, Skill>();
    for (var skill : skillFiles.entrySet()) {
      if (reader.spent()) {
        break;
      }
      reader.read(skill.getKey(), skill.getValue()).ifPresent(read -> keepFirst(skills, read));
    }
    return List.copyOf(skills.values());
  }

  private static void keepFirst(Map<String, Skill> skills, Skill skill) {
    var first = skills.putIfAbsent(skill.name(), skill);
    if (first != null) {
      SkillLog.warn(
          "Skipping skill %s at %s: the skill at %s, found first with that name, is kept",
          skill.name(), skill.directory().orElseThrow(), first.directory().orElseThrow());
    }
  }
}
