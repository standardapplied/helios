/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.memory;

import static com.standardapplied.helios.session.memory.MemoryBackend.PREFIX;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.standardapplied.helios.session.files.WorkspaceRoot;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.FileVisitResult;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Memory's confinement boundary. Maps a {@code /memories/} path to its on-disk location under
 * {@code <workspace>/.agent/memory}, requiring the canonical location to equal the lexical one so
 * no symlink below the memory root is ever traversed, and performs every read, write, creation,
 * deletion and listing through a strict {@link WorkspaceRoot}, even when the workspace it was given
 * is in trusted mode.
 */
final class MemoryFiles {

  private final WorkspaceRoot files;
  private final Path root;

  MemoryFiles(WorkspaceRoot workspace, String storageSubdir) {
    this.files = workspace.confineSymlinks() ? workspace : WorkspaceRoot.of(workspace.root());
    this.root = workspace.root().resolve(storageSubdir).normalize();
  }

  Path root() {
    return root;
  }

  /**
   * Resolve a memory path to its on-disk path.
   *
   * @param path the memory path; non-null
   * @return the absolute, canonical on-disk path
   * @throws IllegalArgumentException if {@code path} is malformed, escapes the memory root, or
   *     traverses a symlink
   */
  Path resolve(String path) {
    Objects.requireNonNull(path, "path must not be null");
    if (!path.startsWith(PREFIX)) {
      throw new IllegalArgumentException("path must start with " + PREFIX + ", got '" + path + "'");
    }
    var rel = path.substring(PREFIX.length());
    if (rel.isEmpty()) {
      throw new IllegalArgumentException("path must name a file under " + PREFIX);
    }
    return confine(path, rel);
  }

  /**
   * Every regular file at or under a memory prefix, as sorted memory paths.
   *
   * @param prefix the memory prefix; non-null, empty means {@link MemoryBackend#PREFIX}
   * @return the memory paths; empty when nothing exists at the prefix
   * @throws IllegalArgumentException if {@code prefix} is malformed or escapes the memory root
   * @throws IOException if the walk fails
   */
  List<String> list(String prefix) throws IOException {
    Objects.requireNonNull(prefix, "prefix must not be null");
    var normalisedPrefix = prefix.isEmpty() ? PREFIX : prefix;
    if (!normalisedPrefix.startsWith(PREFIX)) {
      throw new IllegalArgumentException(
          "prefix must start with " + PREFIX + ", got '" + prefix + "'");
    }
    var start = confine(normalisedPrefix, normalisedPrefix.substring(PREFIX.length()));
    BasicFileAttributes attributes;
    try {
      attributes = files.attributes(start);
    } catch (NoSuchFileException missing) {
      return List.of();
    }
    if (attributes.isRegularFile()) {
      return List.of(toMemoryPath(start));
    }
    var out = new ArrayList<String>();
    files.walkFileTree(
        start,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (attrs.isRegularFile()) {
              out.add(toMemoryPath(file));
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException exc) {
            return FileVisitResult.CONTINUE;
          }
        });
    Collections.sort(out);
    return List.copyOf(out);
  }

  /**
   * Decode a regular-file entry as strict UTF-8.
   *
   * @param path the memory path, for error messages
   * @param resolved its on-disk path
   * @return the entry's content
   * @throws NoSuchFileException naming {@code path} if nothing exists there
   * @throws IOException if the entry is not a regular file, is not valid UTF-8, or cannot be read
   */
  String read(String path, Path resolved) throws IOException {
    BasicFileAttributes attrs;
    try {
      attrs = files.attributes(resolved);
    } catch (NoSuchFileException e) {
      throw new NoSuchFileException(path);
    }
    if (!attrs.isRegularFile()) {
      throw new IOException("memory entry is not a regular file: " + path);
    }
    try (var in = files.newInputStream(resolved)) {
      return UTF_8.newDecoder().decode(ByteBuffer.wrap(in.readAllBytes())).toString();
    }
  }

  void write(Path resolved, byte[] bytes, OpenOption mode) throws IOException {
    try (var out = files.newOutputStream(resolved, mode, StandardOpenOption.WRITE)) {
      out.write(bytes);
    }
  }

  BasicFileAttributes attributes(Path resolved) throws IOException {
    return files.attributes(resolved);
  }

  void createDirectories(Path resolved) throws IOException {
    files.createDirectories(resolved);
  }

  void deleteFile(Path resolved) throws IOException {
    files.deleteFile(resolved);
  }

  private Path confine(String path, String rel) {
    var lexical = root.resolve(rel).normalize();
    if (!lexical.startsWith(root)) {
      throw new IllegalArgumentException("path escapes memory root: " + path);
    }
    var resolved = files.resolveSafe(lexical.toString());
    if (!resolved.equals(lexical)) {
      throw new IllegalArgumentException("path traverses a symlink inside memory: " + path);
    }
    return resolved;
  }

  private String toMemoryPath(Path absolute) {
    var rel = root.relativize(absolute).toString().replace('\\', '/');
    return PREFIX + rel;
  }
}
