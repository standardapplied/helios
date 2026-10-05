/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.memory;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.standardapplied.helios.session.files.WorkspaceRoot;
import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
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
  private final MemoryFiles files;

  private FileSystemMemoryBackend(WorkspaceRoot workspace) {
    this.workspace = Objects.requireNonNull(workspace, "workspace must not be null");
    this.files = new MemoryFiles(workspace, STORAGE_SUBDIR);
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
    return files.root();
  }

  @Override
  public String view(String path) throws IOException {
    return files.read(path, files.resolve(path));
  }

  @Override
  public List<String> list(String prefix) throws IOException {
    return files.list(prefix);
  }

  @Override
  public void create(String path, String content) throws IOException {
    Objects.requireNonNull(content, "content must not be null");
    var resolved = files.resolve(path);
    var bytes = encode(content);
    try {
      files.attributes(resolved);
      throw new FileAlreadyExistsException(path);
    } catch (NoSuchFileException missing) {
    }
    files.createDirectories(resolved.getParent());
    files.write(resolved, bytes, StandardOpenOption.CREATE_NEW);
  }

  @Override
  public void strReplace(String path, String oldString, String newString) throws IOException {
    Objects.requireNonNull(oldString, "oldString must not be null");
    if (oldString.isEmpty()) {
      throw new IllegalArgumentException("oldString must not be empty");
    }
    Objects.requireNonNull(newString, "newString must not be null");
    var resolved = files.resolve(path);
    var content = files.read(path, resolved);
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
    files.write(resolved, encode(updated), StandardOpenOption.TRUNCATE_EXISTING);
  }

  @Override
  public void insert(String path, int lineNumber, String content) throws IOException {
    Objects.requireNonNull(content, "content must not be null");
    var resolved = files.resolve(path);
    var existing = files.read(path, resolved);
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
    files.write(resolved, encode(rebuilt), StandardOpenOption.TRUNCATE_EXISTING);
  }

  @Override
  public void delete(String path) throws IOException {
    var resolved = files.resolve(path);
    if (files.attributes(resolved).isDirectory()) {
      throw new IOException("delete: refusing to delete a directory: " + path);
    }
    files.deleteFile(resolved);
  }

  private static byte[] encode(String content) throws IOException {
    var encoded = UTF_8.newEncoder().encode(CharBuffer.wrap(content));
    var bytes = new byte[encoded.remaining()];
    encoded.get(bytes);
    return bytes;
  }
}
