/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.skills;

import static java.nio.file.FileVisitResult.CONTINUE;
import static java.nio.file.FileVisitResult.SKIP_SUBTREE;
import static java.nio.file.FileVisitResult.TERMINATE;

import com.standardapplied.helios.session.files.GlobTool;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * One walk of a skills root, which finds its skills and lists their files together: a directory
 * holding a {@code SKILL.md} regular file is a skill, and the regular files below it are its files.
 * It follows no link, skips hidden entries and {@code node_modules} silently, reads at most {@link
 * #MAX_DIRECTORIES} directories, and leaves out, with a warning, a directory it cannot read.
 */
final class SkillWalk extends SimpleFileVisitor<Path> {

  /** The file that makes a directory a skill. */
  static final String SKILL_FILE = "SKILL.md";

  /** Levels below a root a skill may sit, and below a skill a file: the loader's own bound. */
  static final int MAX_DEPTH = 4;

  /** Directories one root's walk reads: the loader's own bound, twice {@link #MAX_FILES}. */
  static final int MAX_DIRECTORIES = 2_000;

  /** Files one skill lists: the 1,000 results {@link GlobTool} returns at most. */
  static final int MAX_FILES = 1_000;

  private static final Logger LOGGER = Logger.getLogger(Skills.class.getName());

  private final Path root;
  private final Map<Path, List<String>> skills = new TreeMap<>();
  private final List<Path> links = new ArrayList<>();
  private Path skill;
  private List<String> files;
  private boolean cut;
  private int directories;

  private SkillWalk(Path root) {
    this.root = root;
  }

  /**
   * What one walk found.
   *
   * @param skills each skill's directory, in path order, with the keys of its files, in key order
   * @param links the links met outside any skill, none of them followed
   */
  record Found(Map<Path, List<String>> skills, List<Path> links) {}

  /** Walk {@code root}, a real directory path. */
  static Found walk(Path root) {
    var walk = new SkillWalk(root);
    try {
      Files.walkFileTree(root, walk);
    } catch (IOException e) {
      walk.warn("stopped (" + SkillLog.failed(e) + "); the skills found so far are kept");
    }
    var skills = new TreeMap<Path, List<String>>();
    walk.skills.forEach(
        (directory, keys) -> skills.put(directory, keys.stream().sorted().toList()));
    return new Found(skills, List.copyOf(walk.links));
  }

  @Override
  public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
    if ((!directory.equals(root) && skipped(directory)) || beyondSkill(directory)) {
      return SKIP_SUBTREE;
    }
    if (++directories > MAX_DIRECTORIES) {
      warn("stopped after " + MAX_DIRECTORIES + " directories; the rest are left out");
      return TERMINATE;
    }
    if (skill == null
        && Files.isRegularFile(directory.resolve(SKILL_FILE), LinkOption.NOFOLLOW_LINKS)) {
      skill = directory;
      files = new ArrayList<>();
      cut = false;
      skills.put(directory, files);
    }
    return skill == null && depth(root, directory) == MAX_DEPTH ? SKIP_SUBTREE : CONTINUE;
  }

  @Override
  public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
    if (skipped(file)) {
      return CONTINUE;
    }
    if (skill == null && attributes.isSymbolicLink()) {
      links.add(file);
    } else if (skill != null && attributes.isRegularFile()) {
      list(key(file));
    }
    return CONTINUE;
  }

  @Override
  public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
    if (file.equals(root)) {
      throw e;
    }
    if (!skipped(file) && !beyondSkill(file)) {
      warn("left out " + file + " (" + SkillLog.failed(e) + ")");
    }
    return CONTINUE;
  }

  @Override
  public FileVisitResult postVisitDirectory(Path directory, IOException e) throws IOException {
    if (directory.equals(skill)) {
      skill = null;
    }
    return super.postVisitDirectory(directory, e);
  }

  private void list(String key) {
    if (SKILL_FILE.equals(key)) {
      return;
    }
    if (files.size() < MAX_FILES) {
      files.add(key);
    } else if (!cut) {
      cut = true;
      warn("listed " + MAX_FILES + " files of " + skill + "; the rest are left out");
    }
  }

  private String key(Path file) {
    var key = new StringJoiner("/");
    skill.relativize(file).forEach(segment -> key.add(segment.toString()));
    return key.toString();
  }

  private void warn(String what) {
    SkillLog.warn(LOGGER, () -> "Scanning " + root + " for skills: " + what);
  }

  private static boolean skipped(Path path) {
    var name = path.getFileName().toString();
    return name.startsWith(".") || "node_modules".equals(name);
  }

  /** Whether {@code directory} lies inside the current skill as deep as a file may, or deeper. */
  private boolean beyondSkill(Path directory) {
    return skill != null && depth(skill, directory) >= MAX_DEPTH;
  }

  private static int depth(Path top, Path path) {
    return path.getNameCount() - top.getNameCount();
  }
}
