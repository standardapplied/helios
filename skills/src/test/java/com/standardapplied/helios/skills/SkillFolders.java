/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.skills;

import static org.junit.jupiter.api.Assumptions.abort;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.session.Skill;
import com.standardapplied.helios.session.test.LogCapture;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/** Skill folders on disk, and what a scan of them loaded and warned. */
final class SkillFolders {

  private SkillFolders() {}

  /** What a scan returned and the warnings it logged, in order. */
  record Scanned(List<Skill> skills, List<String> warnings) {

    List<String> names() {
      return skills.stream().map(Skill::name).toList();
    }

    Skill only() {
      if (skills.size() != 1) {
        throw new AssertionError("expected one skill, loaded " + names() + ", warned " + warnings);
      }
      return skills.getFirst();
    }
  }

  /** {@link Skills#load} of {@code roots}, with what it warned. */
  static Scanned load(Path... roots) {
    return scanned(() -> Skills.load(roots));
  }

  /** Runs {@code scan} and records the warnings it logs on the {@link Skills} logger. */
  static Scanned scanned(Supplier<List<Skill>> scan) {
    var records = new ArrayList<LogRecord>();
    List<Skill> skills;
    try (var ignored = LogCapture.of(Skills.class, records)) {
      skills = scan.get();
    }
    return new Scanned(
        skills,
        records.stream()
            .filter(r -> r.getLevel() == Level.WARNING)
            .map(LogRecord::getMessage)
            .toList());
  }

  /** A {@code SKILL.md} with this frontmatter and body. */
  static String skillFile(String frontmatter, String body) {
    return "---\n" + frontmatter + "\n---\n" + body;
  }

  /** A skill named after its directory {@code parent/name}, whose instructions are its name. */
  static Path skill(Path parent, String name) {
    var directory = parent.resolve(name);
    write(directory.resolve("SKILL.md"), skillFile(nameAndDescription(name), name));
    return directory;
  }

  static String nameAndDescription(String name) {
    return "name: " + name + "\ndescription: Does " + name + ".";
  }

  static Path write(Path file, String text) {
    try {
      Files.createDirectories(file.getParent());
      return Files.writeString(file, text);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static Path write(Path file, byte[] bytes) {
    try {
      Files.createDirectories(file.getParent());
      return Files.write(file, bytes);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static Path link(Path link, Path target) {
    try {
      Files.createDirectories(link.getParent());
      return Files.createSymbolicLink(link, target);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A FIFO at {@code path}, made with {@code mkfifo}; skips the test where there is none. */
  static Path fifo(Path path) {
    Process mkfifo;
    try {
      mkfifo = new ProcessBuilder("mkfifo", path.toString()).start();
    } catch (IOException e) {
      return abort("mkfifo is not available: " + e.getMessage());
    }
    Await.termination("mkfifo " + path, mkfifo);
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new AssertionError("mkfifo made no " + path);
    }
    return path;
  }

  /**
   * A sparse file of {@code size} bytes at {@code path}, which costs no disk: a reader that does
   * not stop at its cap fails on it.
   */
  static Path sparse(Path path, long size) {
    try (var file = new RandomAccessFile(write(path, "").toFile(), "rw")) {
      file.setLength(size);
      return path;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static void delete(Path path) {
    try {
      Files.delete(path);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static Path directory(Path directory) {
    try {
      return Files.createDirectories(directory);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static Path real(Path path) {
    try {
      return path.toRealPath();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Makes {@code path} unreadable to this process until the returned handle closes; skips the test
   * when the process reads it anyway, as root does.
   */
  static AutoCloseable unreadable(Path path) throws Exception {
    var permissions = Files.getPosixFilePermissions(path);
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("---------"));
    AutoCloseable restore = () -> Files.setPosixFilePermissions(path, permissions);
    if (Files.isReadable(path)) {
      restore.close();
      abort("this process reads " + path + " whatever its permissions, as root does");
    }
    return restore;
  }
}
