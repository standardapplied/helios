/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.memory;

import java.io.IOException;
import java.util.List;

/**
 * Storage adapter for the agent's persistent memory surface. Five operations split read / write:
 * {@link #view}, {@link #list} for the read side; {@link #create}, {@link #strReplace}, {@link
 * #insert}, {@link #delete} for the write side. Two tools front the interface — {@code MemoryRead}
 * (READ category) and {@code MemoryWrite} (WRITE category) — and the permission system gates write
 * rules separately from read rules.
 *
 * <p>Every path the model supplies is normalised to start with {@code /memories/} — the prefix
 * matches the spec's path-space and lets the permission system match rules with a single glob.
 * Implementations validate the prefix and translate it onto a concrete storage layout (filesystem,
 * Postgres, etc.). Anything outside the prefix is an {@link IllegalArgumentException}.
 *
 * <p>{@link FileSystemMemoryBackend} is the reference implementation; it maps {@code /memories/**}
 * to {@code <workspace>/.agent/memory/**} under the existing {@link
 * ai.singlr.session.files.WorkspaceRoot} path-jail.
 */
public interface MemoryBackend {

  /** The required path prefix for every memory operation. */
  String PREFIX = "/memories/";

  /**
   * Sentinel "memory is intentionally off" backend that refuses every operation with a clear
   * message. Returned by presets that want to surface a deliberate "no memory" stance — applying
   * the sentinel via {@link
   * ai.singlr.session.SessionOptions.Builder#withMemoryBackend(MemoryBackend)} causes any {@code
   * MemoryRead} / {@code MemoryWrite} tool call to fail loudly instead of silently succeeding or
   * NPE-ing.
   *
   * <p>{@link ai.singlr.session.SessionOptions.Builder#withMemoryBackend(MemoryBackend)} accepts
   * {@code null} to mean "no backend configured" — for CodeAct and similar presets that want a
   * permanent stance against memory, prefer the sentinel because it communicates intent and
   * produces a descriptive failure if the model bypasses the permission policy.
   *
   * @return a process-wide singleton sentinel
   */
  static MemoryBackend disabled() {
    return DisabledMemoryBackend.INSTANCE;
  }

  /**
   * Read the text content at {@code path}.
   *
   * @param path the memory path; non-null, non-blank, must begin with {@link #PREFIX}
   * @return the file's UTF-8 content
   * @throws IllegalArgumentException if {@code path} is malformed
   * @throws IOException if reading fails
   * @throws java.nio.file.NoSuchFileException if no entry exists at {@code path}
   */
  String view(String path) throws IOException;

  /**
   * List the entries under {@code prefix}, recursively. Returned paths are normalised back into the
   * {@code /memories/...} namespace, sorted ascending.
   *
   * @param prefix the path prefix; non-null. If empty or equal to {@link #PREFIX}, returns the
   *     whole memory tree.
   * @return immutable list of {@code /memories/...} paths
   * @throws IllegalArgumentException if {@code prefix} is malformed
   * @throws IOException if listing fails
   */
  List<String> list(String prefix) throws IOException;

  /**
   * Create a new memory entry. Refuses to overwrite an existing entry — callers wanting an upsert
   * should {@link #strReplace} or {@link #delete} + create explicitly so the model's intent is
   * always unambiguous.
   *
   * @param path the memory path; non-null, must begin with {@link #PREFIX}
   * @param content the UTF-8 content to write; non-null, may be empty
   * @throws IllegalArgumentException if {@code path} is malformed
   * @throws java.nio.file.FileAlreadyExistsException if an entry already exists at {@code path}
   * @throws IOException if writing fails
   */
  void create(String path, String content) throws IOException;

  /**
   * Replace exactly one occurrence of {@code oldString} with {@code newString} in the file at
   * {@code path}. Mirrors Claude Code's str_replace_editor contract: zero or two-plus matches are
   * an error so the model gets clear feedback to be more specific.
   *
   * @param path the memory path; non-null, must begin with {@link #PREFIX}
   * @param oldString the literal string to find; non-null, non-empty
   * @param newString the replacement; non-null
   * @throws IllegalArgumentException if {@code path} is malformed
   * @throws java.nio.file.NoSuchFileException if no entry exists at {@code path}
   * @throws IOException if {@code oldString} is not found exactly once, or if writing fails
   */
  void strReplace(String path, String oldString, String newString) throws IOException;

  /**
   * Insert {@code content} at the given 1-based {@code lineNumber}. {@code lineNumber == 1} inserts
   * before the first line; {@code lineNumber == lineCount + 1} appends after the last line.
   *
   * @param path the memory path; non-null, must begin with {@link #PREFIX}
   * @param lineNumber 1-based line position; must be in {@code [1, lineCount + 1]}
   * @param content the content to insert; non-null, a trailing newline is added if missing
   * @throws IllegalArgumentException if {@code path} is malformed or {@code lineNumber} is out of
   *     range
   * @throws java.nio.file.NoSuchFileException if no entry exists at {@code path}
   * @throws IOException if writing fails
   */
  void insert(String path, int lineNumber, String content) throws IOException;

  /**
   * Delete the entry at {@code path}.
   *
   * @param path the memory path; non-null, must begin with {@link #PREFIX}
   * @throws IllegalArgumentException if {@code path} is malformed
   * @throws java.nio.file.NoSuchFileException if no entry exists at {@code path}
   * @throws IOException if {@code path} resolves to a directory or deletion fails
   */
  void delete(String path) throws IOException;
}
