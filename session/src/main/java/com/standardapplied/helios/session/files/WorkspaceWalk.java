/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.files;

import com.standardapplied.helios.core.runtime.CancellationToken;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * The search tools' one walk over a workspace subtree, through {@link
 * WorkspaceRoot#walkFileTree(Path, java.nio.file.FileVisitor)}. Hidden directories below the start
 * are pruned, only regular files within the size cap reach the per-file action, unreadable entries
 * are skipped, and the walk stops on cancellation or once the actions have produced the result cap.
 */
final class WorkspaceWalk extends SimpleFileVisitor<Path> {

  /** What a walk does with each regular file it visits. */
  @FunctionalInterface
  interface FileAction {

    /**
     * Process one file.
     *
     * @param file the file's workspace path
     * @param attrs the file's own attributes
     * @param remaining results still allowed before the cap; at least 1
     * @return the number of results produced, at most {@code remaining}
     */
    int visit(Path file, BasicFileAttributes attrs, int remaining);
  }

  private final Path start;
  private final CancellationToken cancellation;
  private final int maxResults;
  private final long maxFileBytes;
  private final FileAction action;
  private int results;

  private WorkspaceWalk(
      Path start,
      CancellationToken cancellation,
      int maxResults,
      long maxFileBytes,
      FileAction action) {
    this.start = start;
    this.cancellation = cancellation;
    this.maxResults = maxResults;
    this.maxFileBytes = maxFileBytes;
    this.action = action;
  }

  /**
   * Walk the subtree under {@code start}.
   *
   * @param workspace the confining workspace
   * @param start a resolved directory
   * @param cancellation stops the walk when cancelled
   * @param maxResults result cap; positive
   * @param maxFileBytes files larger than this are skipped
   * @param action applied to each regular file within the cap
   * @return the number of results the actions produced, at most {@code maxResults}
   * @throws IOException if the walk itself fails
   */
  static int run(
      WorkspaceRoot workspace,
      Path start,
      CancellationToken cancellation,
      int maxResults,
      long maxFileBytes,
      FileAction action)
      throws IOException {
    var walk = new WorkspaceWalk(start, cancellation, maxResults, maxFileBytes, action);
    workspace.walkFileTree(start, walk);
    return walk.results;
  }

  @Override
  public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
    if (cancellation.isCancelled()) {
      return FileVisitResult.TERMINATE;
    }
    if (!dir.equals(start) && dir.getFileName().toString().startsWith(".")) {
      return FileVisitResult.SKIP_SUBTREE;
    }
    return FileVisitResult.CONTINUE;
  }

  @Override
  public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
    if (cancellation.isCancelled()) {
      return FileVisitResult.TERMINATE;
    }
    if (!attrs.isRegularFile() || attrs.size() > maxFileBytes) {
      return FileVisitResult.CONTINUE;
    }
    results += action.visit(file, attrs, maxResults - results);
    return results >= maxResults ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
  }

  @Override
  public FileVisitResult visitFileFailed(Path file, IOException exc) {
    return FileVisitResult.CONTINUE;
  }
}
