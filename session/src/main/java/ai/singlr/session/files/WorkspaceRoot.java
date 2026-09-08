/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.files;

import ai.singlr.core.common.Strings;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Objects;

/**
 * Bounded filesystem root the file tools resolve every model-supplied path against, and the single
 * confinement contract every workspace file operation goes through. Paths that escape the root —
 * lexically via {@code ..}, or via a symlink anywhere in the path — surface as a {@link
 * WorkspaceEscapeException} rather than reaching the underlying I/O layer.
 *
 * <p>{@link #resolveSafe(String)} runs two stages. The lexical stage normalises the input and, for
 * a relative request, requires {@code startsWith(root)}, which refuses {@code ..} without touching
 * the filesystem. The canonical stage then resolves the deepest <em>existing</em> prefix of the
 * path with {@link Path#toRealPath(LinkOption...)}; dangling and looping links are refused
 * outright, and when {@code confineSymlinks} is true the result must stay under the (canonical)
 * root. An absolute request is accepted when either its lexical or its canonical form lies under
 * the root, so a workspace addressed through a symlinked ancestor (e.g. {@code /tmp} on macOS)
 * still accepts absolute paths spelled with that alias. The returned path is the canonical prefix
 * joined with the not-yet-existing suffix, so it never contains a symlink component at resolution
 * time — a new leaf under an ancestor that links outside the root is refused before any side
 * effect.
 *
 * <p>Strict I/O descends directory descriptors with no-follow opens, including the ancestors of the
 * configured root. Files are pinned for metadata inspection before regular-file I/O, so replacing
 * either an ancestor or a leaf with a symlink, FIFO or device cannot redirect or block an open.
 * Directory creation and deletion are also descriptor-relative. A descriptor continues to refer to
 * the entry it opened if that entry is subsequently renamed; this is confinement, not a filesystem
 * snapshot or protection against in-place edits by another writer.
 *
 * <p>Strict mode requires Linux x86-64/AArch64, the default filesystem, mounted {@code
 * /proc/self/fd} and explicit native access: {@code --enable-native-access=ai.singlr.session} on
 * the module path, or {@code --enable-native-access=ALL-UNNAMED} on the class path. Unsupported
 * configurations fail closed. No native library download, compiler or private JDK API is required.
 *
 * <p>{@code confineSymlinks=false} is the explicitly weaker trusted-workspace mode: paths are still
 * canonicalised (so the open primitives keep working no-follow) but the canonical result may lie
 * anywhere a symlink points, including outside the root. It is never the default.
 *
 * <p>{@code root} is canonicalised at construction time, so {@link #root()} can be compared safely;
 * a root reached through a symlinked ancestor (e.g. {@code /tmp} on macOS) is accepted and reported
 * by its real path.
 *
 * @param root the workspace root directory; must exist and be a directory at construction
 * @param confineSymlinks when {@code true}, every canonicalised path is required to stay under the
 *     root; when {@code false}, only the lexical check bounds the request
 */
public record WorkspaceRoot(Path root, boolean confineSymlinks) {

  /**
   * Canonical constructor; canonicalises {@code root}.
   *
   * @throws NullPointerException if {@code root} is null
   * @throws IllegalArgumentException if {@code root} does not exist as a directory or cannot be
   *     canonicalised
   * @throws UnsupportedOperationException if strict mode is unavailable on this platform or native
   *     access has not been enabled
   */
  public WorkspaceRoot {
    Objects.requireNonNull(root, "root must not be null");
    try {
      root = root.toRealPath();
      if (confineSymlinks) {
        LinuxFiles.requireSupport(root);
      }
      var attrs =
          confineSymlinks
              ? LinuxFiles.attributes(root)
              : Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!attrs.isDirectory()) {
        throw new IllegalArgumentException("root is not a directory: " + root);
      }
    } catch (IOException e) {
      throw new IllegalArgumentException("root cannot be canonicalised: " + root, e);
    }
  }

  /**
   * Build a workspace with {@code confineSymlinks=true} — the strict default.
   *
   * @param root the root directory; must exist
   * @return a fresh workspace
   */
  public static WorkspaceRoot of(Path root) {
    return new WorkspaceRoot(root, true);
  }

  /**
   * Resolve a model-supplied path against the root. The input may be a relative path (resolved
   * against the root) or an absolute path under the root or under an alias of it; either way the
   * resolved path is normalised, verified to be inside the root, and canonicalised so it contains
   * no symlink component.
   *
   * @param requested the input path; non-null, non-blank
   * @return the resolved absolute, normalised path inside the workspace
   * @throws NullPointerException if {@code requested} is null
   * @throws WorkspaceEscapeException if the path is blank, syntactically invalid, escapes the root
   *     lexically, traverses a dangling or looping symlink, or (when symlinks are confined)
   *     traverses a symlink that leads outside the root
   */
  public Path resolveSafe(String requested) {
    Objects.requireNonNull(requested, "requested must not be null");
    if (Strings.isBlank(requested)) {
      throw new WorkspaceEscapeException("path must not be blank");
    }
    Path candidate;
    try {
      candidate = Path.of(requested);
    } catch (InvalidPathException e) {
      throw new WorkspaceEscapeException("invalid path: " + e.getMessage());
    }
    Path resolved =
        candidate.isAbsolute() ? candidate.normalize() : root.resolve(candidate).normalize();
    if (!candidate.isAbsolute() && !resolved.startsWith(root)) {
      throw new WorkspaceEscapeException("path escapes workspace root: " + requested);
    }
    var canonical = canonicalise(resolved, requested);
    if (!resolved.startsWith(root) && !canonical.startsWith(root)) {
      throw new WorkspaceEscapeException("path escapes workspace root: " + requested);
    }
    return canonical;
  }

  private Path canonicalise(Path resolved, String requested) {
    var existing = resolved;
    while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
      existing = existing.getParent();
    }
    Path real;
    try {
      real = existing.toRealPath();
    } catch (IOException e) {
      throw new WorkspaceEscapeException(
          "path traverses a dangling or looping symlink: " + requested + ": " + e.getMessage());
    }
    if (confineSymlinks && !real.startsWith(root)) {
      throw new WorkspaceEscapeException("path escapes workspace root via symlink: " + requested);
    }
    return real.resolve(existing.relativize(resolved));
  }

  /**
   * Attributes of the entry at {@code resolved} without following a symlink at the leaf. Check
   * {@link BasicFileAttributes#isRegularFile()} before opening.
   *
   * @param resolved a path previously returned by {@link #resolveSafe(String)}
   * @return the leaf's own attributes
   * @throws IllegalArgumentException if {@code resolved} is not absolute, normalised and confined
   * @throws java.nio.file.NoSuchFileException if nothing exists at {@code resolved}
   * @throws IOException if an ancestor is a symlink or the attributes cannot be read
   */
  public BasicFileAttributes attributes(Path resolved) throws IOException {
    requireResolved(resolved);
    if (confineSymlinks) {
      return LinuxFiles.attributes(resolved);
    }
    return Files.readAttributes(resolved, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
  }

  /**
   * Open {@code resolved} for reading without following a symlink at the leaf.
   *
   * @param resolved a path previously returned by {@link #resolveSafe(String)}
   * @return an open stream
   * @throws IllegalArgumentException if {@code resolved} is not absolute, normalised and confined
   * @throws IOException if a component is a symlink, the leaf is not regular, or opening fails
   */
  public InputStream newInputStream(Path resolved) throws IOException {
    return newInputStream(resolved, Long.MAX_VALUE);
  }

  /**
   * Read a regular file with a byte limit, checked on the pinned target and during reading.
   *
   * @param resolved a resolved file path
   * @param maxBytes maximum readable bytes; non-negative
   * @return a caller-owned stream
   * @throws IOException if opening fails, the target is not regular, or the limit is exceeded
   */
  public InputStream newInputStream(Path resolved, long maxBytes) throws IOException {
    requireResolved(resolved);
    if (confineSymlinks) {
      return LinuxFiles.input(resolved, maxBytes);
    }
    if (maxBytes < 0) {
      throw new IllegalArgumentException("maxBytes must not be negative");
    }
    var attrs = attributes(resolved);
    if (!attrs.isRegularFile() || attrs.size() > maxBytes) {
      throw new IOException("entry is not a regular file within the size limit: " + resolved);
    }
    return LinuxFiles.limit(Files.newInputStream(resolved, LinkOption.NOFOLLOW_LINKS), maxBytes);
  }

  /**
   * Open {@code resolved} for writing without following a symlink at the leaf. Callers choose the
   * create/truncate semantics via {@code options} ({@link
   * java.nio.file.StandardOpenOption#CREATE_NEW} for create, {@link
   * java.nio.file.StandardOpenOption#TRUNCATE_EXISTING} for replace). With no options, creates a
   * missing file or truncates an existing file, as {@link Files#newOutputStream(Path,
   * OpenOption...)} does. Strict-mode files are created owner-only, further restricted by the
   * process umask. {@link StandardOpenOption#DELETE_ON_CLOSE} is unsupported in strict mode.
   *
   * @param resolved a path previously returned by {@link #resolveSafe(String)}
   * @param options open options; {@link LinkOption#NOFOLLOW_LINKS} is always added
   * @return an open stream
   * @throws IllegalArgumentException if the path or options are invalid
   * @throws UnsupportedOperationException if an option is unsupported
   * @throws IOException if a component is a symlink, the leaf is not regular, or opening fails
   */
  public OutputStream newOutputStream(Path resolved, OpenOption... options) throws IOException {
    Objects.requireNonNull(options, "options must not be null");
    requireResolved(resolved);
    if (confineSymlinks) {
      return LinuxFiles.output(resolved, options);
    }
    var effectiveOptions =
        options.length == 0
            ? new OpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING}
            : options;
    var withNoFollow =
        Arrays.copyOf(effectiveOptions, effectiveOptions.length + 1, OpenOption[].class);
    withNoFollow[effectiveOptions.length] = LinkOption.NOFOLLOW_LINKS;
    return Files.newOutputStream(requireResolved(resolved), withNoFollow);
  }

  /**
   * Create missing directories beneath this root without following any symlink component.
   * Strict-mode directories are owner-only, further restricted by the process umask.
   *
   * @param resolved a resolved directory path
   * @throws IOException if creation fails or a component is not a directory
   */
  public void createDirectories(Path resolved) throws IOException {
    requireResolved(resolved);
    if (confineSymlinks) {
      LinuxFiles.createDirectories(root, resolved);
    } else {
      Files.createDirectories(resolved);
    }
  }

  /**
   * Delete a non-directory entry. A raced leaf symlink is unlinked, never followed.
   *
   * @param resolved a resolved file path
   * @throws IOException if the entry is missing, is a directory, or cannot be deleted
   */
  public void deleteFile(Path resolved) throws IOException {
    requireResolved(resolved);
    if (confineSymlinks) {
      LinuxFiles.deleteFile(resolved);
    } else {
      if (attributes(resolved).isDirectory()) {
        throw new IOException("refusing to delete a directory: " + resolved);
      }
      Files.delete(resolved);
    }
  }

  /**
   * Enumerate a pinned directory, returning logical workspace paths rather than descriptor paths.
   *
   * @param resolved a resolved directory path
   * @return a caller-owned directory stream
   * @throws IOException if the directory cannot be opened without following links
   */
  public DirectoryStream<Path> newDirectoryStream(Path resolved) throws IOException {
    requireResolved(resolved);
    return confineSymlinks ? LinuxFiles.entries(resolved) : Files.newDirectoryStream(resolved);
  }

  /**
   * Walk without following links, using the same confined operations for every opened entry.
   *
   * @param start a resolved starting path
   * @param visitor traversal callbacks
   * @throws IOException if traversal or a visitor fails
   */
  public void walkFileTree(Path start, FileVisitor<? super Path> visitor) throws IOException {
    requireResolved(start);
    Objects.requireNonNull(visitor, "visitor must not be null");
    try (var stack = new WalkStack()) {
      Path next = start;
      while (next != null) {
        FileVisitResult result;
        BasicFileAttributes attrs;
        try {
          attrs = attributes(next);
        } catch (IOException failure) {
          result = visitor.visitFileFailed(next, failure);
          if (result == FileVisitResult.TERMINATE) {
            return;
          }
          next = stack.next(visitor, result);
          continue;
        }
        if (attrs.isDirectory()) {
          result = visitor.preVisitDirectory(next, attrs);
          if (result == FileVisitResult.CONTINUE) {
            try {
              stack.push(new WalkFrame(next, newDirectoryStream(next)));
            } catch (IOException failure) {
              result = visitor.visitFileFailed(next, failure);
            }
          }
        } else {
          result = visitor.visitFile(next, attrs);
        }
        if (result == FileVisitResult.TERMINATE) {
          return;
        }
        next = stack.next(visitor, result);
      }
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

  private Path requireResolved(Path resolved) {
    Objects.requireNonNull(resolved, "resolved must not be null");
    if (!resolved.isAbsolute()
        || !resolved.normalize().equals(resolved)
        || (confineSymlinks && !resolved.startsWith(root))) {
      throw new IllegalArgumentException(
          "path was not resolved through this workspace root: " + resolved);
    }
    return resolved;
  }

  /**
   * The model-supplied path made relative to the workspace root, for display in tool results.
   * Returns the absolute path if {@code resolved} is not actually under the root (defensive — the
   * caller normally passes a {@link #resolveSafe(String)} result).
   *
   * @param resolved a path; non-null
   * @return a relative path string suitable for tool output
   */
  public String relativize(Path resolved) {
    Objects.requireNonNull(resolved, "resolved must not be null");
    if (!resolved.startsWith(root)) {
      return resolved.toString();
    }
    var rel = root.relativize(resolved).toString();
    return rel.isEmpty() ? "." : rel;
  }

  /** Thrown by {@link #resolveSafe(String)} when a request fails the path-jail check. */
  public static final class WorkspaceEscapeException extends RuntimeException {

    /**
     * @param message human-readable reason
     */
    public WorkspaceEscapeException(String message) {
      super(message);
    }
  }
}
