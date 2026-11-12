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
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * One walk of a skills root, which finds its skills and lists their files together: a directory
 * holding a {@code SKILL.md} regular file is a skill, and the regular files below it are its files.
 * It follows no link, reporting the links it meets outside a skill in one warning, skips hidden
 * entries and {@code node_modules} silently, stops at its bounds, and leaves out, with a warning, a
 * directory it cannot read.
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

  /**
   * Files one root's walk lists, all skills together, ten times {@link #MAX_FILES}: an empty, too
   * large or non-UTF-8 file costs nothing against the call's byte budget, so without this the names
   * alone would be bounded only by the checkout.
   */
  static final int MAX_ROOT_FILES = 10 * MAX_FILES;

  private static final int LINKS_NAMED = 3;

  private final Path root;
  private final Map<Path, List<String>> skills = new TreeMap<>();
  private final TreeSet<Path> links = new TreeSet<>();
  private Path skill;
  private List<String> files;
  private boolean cut;
  private int directories;
  private int listed;

  SkillWalk(Path root) {
    this.root = root;
  }

  /**
   * The skills below {@code root}, a real directory path: each skill's directory, in path order,
   * with the keys of its files, in key order.
   */
  static Map<Path, List<String>> walk(Path root) {
    var walk = new SkillWalk(root);
    try {
      Files.walkFileTree(root, walk);
    } catch (IOException e) {
      SkillLog.warn(
          "Scanning %s for skills: stopped (%s); the skills found so far are kept",
          root, SkillLog.failed(e));
    }
    walk.warnLinks();
    walk.skills.values().forEach(keys -> keys.sort(null));
    return walk.skills;
  }

  @Override
  public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
    if ((!directory.equals(root) && skipped(directory)) || beyondSkill(directory)) {
      return SKIP_SUBTREE;
    }
    if (++directories > MAX_DIRECTORIES) {
      SkillLog.warn(
          "Scanning %s for skills: stopped after %s directories; the rest are left out",
          root, MAX_DIRECTORIES);
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
      return list(key(file));
    }
    return CONTINUE;
  }

  @Override
  public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
    if (file.equals(root)) {
      throw e;
    }
    if (!skipped(file) && !beyondSkill(file)) {
      SkillLog.warn("Scanning %s for skills: left out %s (%s)", root, file, SkillLog.failed(e));
    }
    return CONTINUE;
  }

  @Override
  public FileVisitResult postVisitDirectory(Path directory, IOException e) throws IOException {
    if (directory.equals(skill)) {
      skill = null;
    }
    return e == null ? CONTINUE : visitFileFailed(directory, e);
  }

  private FileVisitResult list(String key) {
    if (SKILL_FILE.equals(key)) {
      return CONTINUE;
    }
    if (files.size() == MAX_FILES) {
      if (!cut) {
        cut = true;
        SkillLog.warn(
            "Scanning %s for skills: listed %s files of %s; the rest are left out",
            root, MAX_FILES, skill);
      }
      return CONTINUE;
    }
    if (listed == MAX_ROOT_FILES) {
      SkillLog.warn(
          "Scanning %s for skills: stopped after %s files; the rest are left out",
          root, MAX_ROOT_FILES);
      return TERMINATE;
    }
    listed++;
    files.add(key);
    return CONTINUE;
  }

  private void warnLinks() {
    if (links.isEmpty()) {
      return;
    }
    var more = links.size() - LINKS_NAMED;
    SkillLog.warn(
        "Scanning %s for skills: links not followed: %s%s; pass a link's target to Skills.load"
            + " to read the skills there",
        root,
        links.stream().limit(LINKS_NAMED).map(Path::toString).collect(Collectors.joining(", ")),
        more > 0 ? " and " + more + " more" : "");
  }

  private String key(Path file) {
    var key = new StringJoiner("/");
    skill.relativize(file).forEach(segment -> key.add(segment.toString()));
    return key.toString();
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
