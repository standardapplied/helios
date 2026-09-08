/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.files;

import ai.singlr.core.common.Strings;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
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
 * <p>Because no JDK API exposes {@code openat2(RESOLVE_BENEATH)}, resolution alone cannot stop
 * another actor from swapping a component between resolve and open. The open primitives here
 * ({@link #attributes}, {@link #newInputStream}, {@link #newOutputStream}) refuse any path whose
 * parent chain is not real — an unresolved directory symlink anywhere above the leaf is rejected,
 * not followed — and close the leaf-level window by always passing {@link
 * LinkOption#NOFOLLOW_LINKS}: a leaf replaced by a symlink fails to open instead of being followed.
 * Callers must additionally check {@link BasicFileAttributes#isRegularFile()} before opening so
 * FIFOs and device files are never opened. An ancestor directory swapped for a symlink between that
 * check and the open remains a residual the platform cannot close from Java; OS-level sandboxing
 * stays the authoritative boundary.
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
   */
  public WorkspaceRoot {
    Objects.requireNonNull(root, "root must not be null");
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("root is not a directory: " + root);
    }
    try {
      root = root.toRealPath();
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
   * @throws IllegalArgumentException if {@code resolved} was not resolved through this root or
   *     contains an unresolved ancestor symlink
   * @throws java.nio.file.NoSuchFileException if nothing exists at {@code resolved}
   * @throws IOException if the attributes cannot be read
   */
  public BasicFileAttributes attributes(Path resolved) throws IOException {
    return Files.readAttributes(
        requireResolved(resolved), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
  }

  /**
   * Open {@code resolved} for reading without following a symlink at the leaf.
   *
   * @param resolved a path previously returned by {@link #resolveSafe(String)}
   * @return an open stream
   * @throws IllegalArgumentException if {@code resolved} was not resolved through this root or
   *     contains an unresolved ancestor symlink
   * @throws IOException if the leaf is a symlink, is missing, or cannot be opened
   */
  public InputStream newInputStream(Path resolved) throws IOException {
    return Files.newInputStream(requireResolved(resolved), LinkOption.NOFOLLOW_LINKS);
  }

  /**
   * Open {@code resolved} for writing without following a symlink at the leaf. Callers choose the
   * create/truncate semantics via {@code options} ({@link
   * java.nio.file.StandardOpenOption#CREATE_NEW} for create, {@link
   * java.nio.file.StandardOpenOption#TRUNCATE_EXISTING} for replace). With no options, creates a
   * missing file or truncates an existing file, as {@link Files#newOutputStream(Path,
   * OpenOption...)} does.
   *
   * @param resolved a path previously returned by {@link #resolveSafe(String)}
   * @param options open options; {@link LinkOption#NOFOLLOW_LINKS} is always added
   * @return an open stream
   * @throws IllegalArgumentException if {@code resolved} was not resolved through this root or
   *     contains an unresolved ancestor symlink
   * @throws IOException if the leaf is a symlink or cannot be opened with {@code options}
   */
  public OutputStream newOutputStream(Path resolved, OpenOption... options) throws IOException {
    Objects.requireNonNull(options, "options must not be null");
    var effectiveOptions =
        options.length == 0
            ? new OpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING}
            : options;
    var withNoFollow =
        Arrays.copyOf(effectiveOptions, effectiveOptions.length + 1, OpenOption[].class);
    withNoFollow[effectiveOptions.length] = LinkOption.NOFOLLOW_LINKS;
    return Files.newOutputStream(requireResolved(resolved), withNoFollow);
  }

  private Path requireResolved(Path resolved) {
    Objects.requireNonNull(resolved, "resolved must not be null");
    if (!resolved.isAbsolute()
        || !resolved.normalize().equals(resolved)
        || (confineSymlinks && !resolved.startsWith(root))
        || !parentIsReal(resolved)) {
      throw new IllegalArgumentException(
          "path was not resolved through this workspace root: " + resolved);
    }
    return resolved;
  }

  private boolean parentIsReal(Path resolved) {
    var parent = resolved.equals(root) ? root : resolved.getParent();
    try {
      return canonicalise(parent, parent.toString()).equals(parent);
    } catch (WorkspaceEscapeException e) {
      return false;
    }
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
