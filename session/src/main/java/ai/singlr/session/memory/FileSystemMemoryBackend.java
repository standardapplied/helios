/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.memory;

import static java.nio.charset.StandardCharsets.UTF_8;

import ai.singlr.session.files.WorkspaceRoot;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.file.FileAlreadyExistsException;
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
 * Filesystem-backed {@link MemoryBackend} that stores memory under {@code
 * <workspace>/.agent/memory} under an existing {@link WorkspaceRoot}. The {@code /memories/}
 * virtual prefix is stripped and re-joined when listing, so the model never sees the on-disk path.
 *
 * <p>The backend creates the root directory lazily on first write. {@code view} on a missing entry
 * throws {@link NoSuchFileException}; {@code list} on a missing root returns the empty list (a
 * fresh workspace has nothing to list yet, which should not be an error).
 *
 * <p>Memory has a strictly narrower confinement contract than the workspace tools. Every path
 * routes through {@link WorkspaceRoot#resolveSafe(String)} and must canonicalise to exactly its
 * lexical location under the canonical memory root, so a symlink anywhere below {@code
 * .agent/memory} — even one pointing at another file inside the workspace — is refused before any
 * side effect, whichever {@code confineSymlinks} mode the workspace uses. Reads, writes, creation,
 * deletion and listing use strict descriptor-relative workspace operations, including when the
 * supplied workspace is in trusted mode. The strict platform and native-access requirements apply.
 *
 * <p>Content is strict UTF-8: a file that is not valid UTF-8 fails to read rather than being
 * silently rewritten with replacement characters, and content that cannot be encoded (unpaired
 * surrogates) is rejected before the target is opened, so an existing entry is never truncated.
 */
public final class FileSystemMemoryBackend implements MemoryBackend {

  /** The on-disk subdirectory under the workspace where memory lives. */
  public static final String STORAGE_SUBDIR = ".agent/memory";

  private final WorkspaceRoot workspace;
  private final WorkspaceRoot files;
  private final Path memoryRoot;

  private FileSystemMemoryBackend(WorkspaceRoot workspace) {
    this.workspace = Objects.requireNonNull(workspace, "workspace must not be null");
    this.files = workspace.confineSymlinks() ? workspace : WorkspaceRoot.of(workspace.root());
    this.memoryRoot = workspace.root().resolve(STORAGE_SUBDIR).normalize();
  }

  /**
   * Build a backend rooted under the given workspace.
   *
   * @param workspace the workspace; non-null
   * @return a fresh backend
   */
  public static FileSystemMemoryBackend of(WorkspaceRoot workspace) {
    return new FileSystemMemoryBackend(workspace);
  }

  /**
   * The workspace this backend is anchored under.
   *
   * @return the workspace root
   */
  public WorkspaceRoot workspace() {
    return workspace;
  }

  /**
   * The on-disk directory memory is stored under.
   *
   * @return absolute path
   */
  public Path memoryRoot() {
    return memoryRoot;
  }

  @Override
  public String view(String path) throws IOException {
    return read(path, resolveMemoryPath(path));
  }

  @Override
  public List<String> list(String prefix) throws IOException {
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

  @Override
  public void create(String path, String content) throws IOException {
    Objects.requireNonNull(content, "content must not be null");
    var resolved = resolveMemoryPath(path);
    var bytes = encode(content);
    try {
      files.attributes(resolved);
      throw new FileAlreadyExistsException(path);
    } catch (NoSuchFileException missing) {
    }
    files.createDirectories(resolved.getParent());
    write(resolved, bytes, StandardOpenOption.CREATE_NEW);
  }

  @Override
  public void strReplace(String path, String oldString, String newString) throws IOException {
    Objects.requireNonNull(oldString, "oldString must not be null");
    if (oldString.isEmpty()) {
      throw new IllegalArgumentException("oldString must not be empty");
    }
    Objects.requireNonNull(newString, "newString must not be null");
    var resolved = resolveMemoryPath(path);
    var content = read(path, resolved);
    var first = content.indexOf(oldString);
    if (first < 0) {
      throw new IOException("strReplace: oldString not found in " + path);
    }
    var second = content.indexOf(oldString, first + 1);
    if (second >= 0) {
      throw new IOException(
          "strReplace: oldString appears more than once in "
              + path
              + " — provide more context so the match is unique");
    }
    var updated =
        content.substring(0, first) + newString + content.substring(first + oldString.length());
    write(resolved, updated, StandardOpenOption.TRUNCATE_EXISTING);
  }

  @Override
  public void insert(String path, int lineNumber, String content) throws IOException {
    Objects.requireNonNull(content, "content must not be null");
    var resolved = resolveMemoryPath(path);
    var existing = read(path, resolved);
    var lines = new ArrayList<>(List.of(existing.split("\n", -1)));
    // split("\n", -1) on "" yields [""] — a single empty trailing element. Drop it for empty files
    // so a 1-line file becomes [singleLine] not [singleLine, ""].
    if (lines.size() == 1 && lines.get(0).isEmpty()) {
      lines.clear();
    } else if (existing.endsWith("\n")) {
      // a trailing newline always emits an empty trailing element from split(-1); drop it so
      // lineNumber semantics match user expectations
      lines.remove(lines.size() - 1);
    }
    if (lineNumber < 1 || lineNumber > lines.size() + 1) {
      throw new IllegalArgumentException(
          "lineNumber " + lineNumber + " out of range [1, " + (lines.size() + 1) + "]");
    }
    var toInsert = content.endsWith("\n") ? content.substring(0, content.length() - 1) : content;
    lines.add(lineNumber - 1, toInsert);
    var rebuilt = String.join("\n", lines);
    if (existing.endsWith("\n") || existing.isEmpty()) {
      rebuilt = rebuilt + "\n";
    }
    write(resolved, rebuilt, StandardOpenOption.TRUNCATE_EXISTING);
  }

  @Override
  public void delete(String path) throws IOException {
    var resolved = resolveMemoryPath(path);
    if (files.attributes(resolved).isDirectory()) {
      throw new IOException("delete: refusing to delete a directory: " + path);
    }
    files.deleteFile(resolved);
  }

  /**
   * Resolve a {@code /memories/...} path to an on-disk path, validating the prefix, routing through
   * the workspace's path-jail, and requiring the canonical location to equal the lexical one under
   * the memory root so no symlink component is ever traversed.
   *
   * @param path the memory path; non-null
   * @return the absolute, canonical on-disk path
   * @throws IllegalArgumentException if {@code path} is malformed, escapes the memory root, or
   *     traverses a symlink
   */
  Path resolveMemoryPath(String path) {
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

  private Path confine(String path, String rel) {
    var lexical = memoryRoot.resolve(rel).normalize();
    if (!lexical.startsWith(memoryRoot)) {
      throw new IllegalArgumentException("path escapes memory root: " + path);
    }
    var resolved = files.resolveSafe(lexical.toString());
    if (!resolved.equals(lexical)) {
      throw new IllegalArgumentException("path traverses a symlink inside memory: " + path);
    }
    return resolved;
  }

  private String read(String path, Path resolved) throws IOException {
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

  private void write(Path resolved, String content, OpenOption mode) throws IOException {
    write(resolved, encode(content), mode);
  }

  private static byte[] encode(String content) throws IOException {
    var encoded = UTF_8.newEncoder().encode(CharBuffer.wrap(content));
    var bytes = new byte[encoded.remaining()];
    encoded.get(bytes);
    return bytes;
  }

  private void write(Path resolved, byte[] bytes, OpenOption mode) throws IOException {
    try (var out = files.newOutputStream(resolved, mode, StandardOpenOption.WRITE)) {
      out.write(bytes);
    }
  }

  private String toMemoryPath(Path absolute) {
    var rel = memoryRoot.relativize(absolute).toString().replace('\\', '/');
    return PREFIX + rel;
  }
}
