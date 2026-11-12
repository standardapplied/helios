/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.skills;

import com.standardapplied.helios.session.Skill;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reads Agent Skills folders (agentskills.io) into {@link Skill} values for {@code
 * SessionOptions.Builder.withSkills}.
 *
 * <p>Every directory up to four levels below a folder that holds a {@code SKILL.md} regular file is
 * a skill, the folder itself included. Its {@code SKILL.md}, at most 1 MiB, is YAML frontmatter
 * between {@code ---} lines, of which {@code name} and {@code description} are read, then the body,
 * its instructions. Its other regular files, to four levels and at most 1,000, are its files. Below
 * a folder no link is followed, hidden entries and {@code node_modules} are skipped, and at most
 * 2,000 directories are read.
 *
 * <p>A loaded skill is a snapshot: a call reads everything once, and nothing touches the disk after
 * it returns. A file of at most 60,000 bytes of UTF-8 is kept as text; a larger or binary one is
 * listed, for a script to use at its path, and reading it fails. One call keeps at most 16 MiB of
 * text. A change on disk is seen by calling again. Load skills while nothing else writes their
 * folders: a writer racing the call can make it read another file than it listed, or wait on a
 * FIFO.
 *
 * <p>Reading is lenient: a skill or file that cannot be loaded, including one whose name,
 * description, directory or file path {@code Skill} refuses, is left out and the rest load. Each
 * problem, and each departure from the standard that still loads, is a {@code WARNING} on the
 * logger {@code com.standardapplied.helios.skills.Skills}, the only report. When two skills share a
 * name, the first found wins. Results are in folder order, then by directory path.
 */
public final class Skills {

  private static final Logger LOGGER = Logger.getLogger(Skills.class.getName());
  private static final int LINKS_NAMED = 3;

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
    return realDirectory(path).filter(real -> inside(project, path, real));
  }

  private static boolean inside(Path project, Path path, Path real) {
    if (!real.startsWith(project)) {
      SkillLog.warn(
          LOGGER,
          () ->
              "Skipping "
                  + path
                  + ": it leads through a link to "
                  + real
                  + ", outside the project "
                  + project
                  + "; pass that folder to Skills.load if you trust it");
    }
    return real.startsWith(project);
  }

  private static List<Skill> scan(List<Path> roots) {
    var directories = new LinkedHashMap<Path, List<String>>();
    var links = new ArrayList<Path>();
    for (var root : roots.stream().distinct().toList()) {
      var found = SkillWalk.walk(root);
      found.skills().forEach(directories::putIfAbsent);
      links.addAll(found.links());
    }
    warnLinks(links);
    var reader = new SkillReader();
    var skills = new LinkedHashMap<String, Skill>();
    for (var skill : directories.entrySet()) {
      if (reader.spent()) {
        break;
      }
      reader.read(skill.getKey(), skill.getValue()).ifPresent(read -> keepFirst(skills, read));
    }
    return List.copyOf(skills.values());
  }

  private static void warnLinks(List<Path> links) {
    if (links.isEmpty()) {
      return;
    }
    var named =
        links.stream()
            .sorted()
            .limit(LINKS_NAMED)
            .map(Path::toString)
            .collect(Collectors.joining(", "));
    var more = links.size() > LINKS_NAMED ? " and " + (links.size() - LINKS_NAMED) + " more" : "";
    SkillLog.warn(
        LOGGER,
        () ->
            "Not following "
                + links.size()
                + " links met while scanning for skills ("
                + named
                + more
                + "); pass a link's target to Skills.load to read the skills there");
  }

  private static void keepFirst(Map<String, Skill> skills, Skill skill) {
    var first = skills.putIfAbsent(skill.name(), skill);
    if (first != null) {
      SkillLog.warn(
          LOGGER,
          () ->
              "Skipping skill "
                  + skill.name()
                  + " at "
                  + skill.directory().orElseThrow()
                  + ": the skill at "
                  + first.directory().orElseThrow()
                  + ", found first with that name, is kept");
    }
  }
}
