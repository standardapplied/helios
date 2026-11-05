/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.files;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * The depth-first walk behind {@link WorkspaceRoot#walkFileTree(Path, FileVisitor)}. It never
 * follows a link: every entry's attributes and every directory's listing come from the workspace's
 * own confined operations, so a walk is confined exactly as a single open is.
 */
final class FileTreeWalk {

  private FileTreeWalk() {}

  static void walk(WorkspaceRoot workspace, Path start, FileVisitor<? super Path> visitor)
      throws IOException {
    try (var stack = new WalkStack()) {
      var next = start;
      while (next != null) {
        var result = visit(workspace, next, visitor, stack);
        if (result == FileVisitResult.TERMINATE) {
          return;
        }
        next = stack.next(visitor, result);
      }
    }
  }

  private static FileVisitResult visit(
      WorkspaceRoot workspace, Path entry, FileVisitor<? super Path> visitor, WalkStack stack)
      throws IOException {
    BasicFileAttributes attrs;
    try {
      attrs = workspace.attributes(entry);
    } catch (IOException failure) {
      return visitor.visitFileFailed(entry, failure);
    }
    if (!attrs.isDirectory()) {
      return visitor.visitFile(entry, attrs);
    }
    var result = visitor.preVisitDirectory(entry, attrs);
    return result == FileVisitResult.CONTINUE ? push(workspace, entry, visitor, stack) : result;
  }

  private static FileVisitResult push(
      WorkspaceRoot workspace, Path directory, FileVisitor<? super Path> visitor, WalkStack stack)
      throws IOException {
    try {
      stack.push(new WalkFrame(directory, workspace.newDirectoryStream(directory)));
      return FileVisitResult.CONTINUE;
    } catch (IOException failure) {
      return visitor.visitFileFailed(directory, failure);
    }
  }

  private static final class WalkFrame implements AutoCloseable {
    final Path path;
    final DirectoryStream<Path> stream;
    final Iterator<Path> iterator;
    boolean skipSiblings;

    WalkFrame(Path path, DirectoryStream<Path> stream) throws IOException {
      this.path = path;
      this.stream = stream;
      try {
        this.iterator = stream.iterator();
      } catch (Throwable failure) {
        try (stream) {
          throw failure;
        }
      }
    }

    @Override
    public void close() throws IOException {
      stream.close();
    }
  }

  private static final class WalkStack implements AutoCloseable {
    private final ArrayDeque<WalkFrame> frames = new ArrayDeque<>();

    void push(WalkFrame frame) {
      frames.push(frame);
    }

    Path next(FileVisitor<? super Path> visitor, FileVisitResult result) throws IOException {
      while (!frames.isEmpty()) {
        var frame = frames.peek();
        frame.skipSiblings |= result == FileVisitResult.SKIP_SIBLINGS;
        IOException failure = null;
        try {
          if (!frame.skipSiblings && frame.iterator.hasNext()) {
            return frame.iterator.next();
          }
        } catch (DirectoryIteratorException e) {
          failure = e.getCause();
        }
        frames.pop().close();
        result = visitor.postVisitDirectory(frame.path, failure);
        if (result == FileVisitResult.TERMINATE) {
          return null;
        }
      }
      return null;
    }

    @Override
    public void close() throws IOException {
      IOException failure = null;
      while (!frames.isEmpty()) {
        try {
          frames.pop().close();
        } catch (IOException e) {
          if (failure == null) {
            failure = e;
          } else if (failure != e) {
            failure.addSuppressed(e);
          }
        }
      }
      if (failure != null) {
        throw failure;
      }
    }
  }
}
