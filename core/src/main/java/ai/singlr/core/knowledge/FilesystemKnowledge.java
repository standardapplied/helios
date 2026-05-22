/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.knowledge;

import ai.singlr.core.common.SecretRegistry;
import ai.singlr.core.tool.ParameterType;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolContext;
import ai.singlr.core.tool.ToolParameter;
import ai.singlr.core.tool.ToolResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Read-only access to a curated filesystem corpus, exposed to an agent as three tools: {@code
 * kb_grep}, {@code kb_glob}, and {@code kb_read}. Designed as an alternative to embedding +
 * vector-DB recall when the corpus is bounded and the operator wants the model to find data via
 * lexical search and full reads — the same pattern Claude Code uses.
 *
 * <h2>Security model</h2>
 *
 * <ul>
 *   <li><b>Path jail.</b> Every model-supplied path is resolved against the configured root,
 *       normalized, and refused if it escapes via {@code ..} or names an absolute path. After
 *       resolution, {@code toRealPath} is consulted to refuse symlink escapes. See {@link
 *       PathJail}.
 *   <li><b>No symlinks.</b> Symlinks encountered during traversal are skipped. A {@code kb_read} on
 *       a symlink target outside the root is refused.
 *   <li><b>Hidden directories skipped.</b> Directories whose name begins with a dot (e.g. {@code
 *       .git}) are not descended into. Operators can override with a custom path filter.
 *   <li><b>Resource caps everywhere.</b> Maximum file size, maximum bytes per read, maximum grep
 *       results, maximum glob results, and a wall-clock deadline on every grep. Exceeding any cap
 *       returns partial results with an explicit truncation marker.
 *   <li><b>Binary files skipped.</b> Files whose first 8 KB contains a NUL byte are treated as
 *       binary and ignored by grep.
 *   <li><b>Output redaction.</b> Every tool's output is run through the configured {@link
 *       SecretRegistry}'s redactor before being returned to the model. Operators sharing a registry
 *       with a {@code CommandGrant} get cross-tool redaction for free.
 *   <li><b>Read-only.</b> No write surface. A {@code kb_write} would need a separate, opt-in
 *       construction with a different security review.
 * </ul>
 *
 * <h2>Typical use</h2>
 *
 * <pre>{@code
 * var registry = new SecretRegistry();
 * var kb = FilesystemKnowledge.builder(Path.of("/var/kb/support"))
 *     .withSecretRegistry(registry)
 *     .withMaxFileSize(1_000_000)
 *     .withMaxBytesPerRead(50_000)
 *     .withMaxGrepResults(100)
 *     .build();
 *
 * agentConfig.tools().addAll(kb.tools());
 * }</pre>
 */
public final class FilesystemKnowledge {

  private static final int BINARY_SNIFF_BYTES = 8 * 1024;
  private static final int DEFAULT_MAX_FILE_SIZE = 1_000_000;
  private static final int DEFAULT_MAX_BYTES_PER_READ = 50_000;
  private static final int DEFAULT_MAX_GREP_RESULTS = 100;
  private static final int DEFAULT_MAX_GLOB_RESULTS = 500;
  private static final Duration DEFAULT_GREP_TIMEOUT = Duration.ofSeconds(10);

  private final PathJail jail;
  private final SecretRegistry secretRegistry;
  private final int maxFileSize;
  private final int maxBytesPerRead;
  private final int maxGrepResults;
  private final int maxGlobResults;
  private final Duration grepTimeout;
  private final boolean skipHidden;
  private final Predicate<Path> pathFilter;

  private FilesystemKnowledge(Builder b, PathJail jail, SecretRegistry registry) {
    this.jail = jail;
    this.secretRegistry = registry;
    this.maxFileSize = b.maxFileSize;
    this.maxBytesPerRead = b.maxBytesPerRead;
    this.maxGrepResults = b.maxGrepResults;
    this.maxGlobResults = b.maxGlobResults;
    this.grepTimeout = b.grepTimeout;
    this.skipHidden = b.skipHidden;
    this.pathFilter = b.pathFilter;
  }

  /** Start a builder rooted at the given directory. */
  public static Builder builder(Path root) {
    return new Builder(root);
  }

  /** The root directory this knowledge base exposes. */
  public Path root() {
    return jail.root();
  }

  /** The {@link SecretRegistry} this knowledge base redacts against. */
  public SecretRegistry secretRegistry() {
    return secretRegistry;
  }

  /** The three knowledge tools, in declaration order: grep, glob, read. */
  public List<Tool> tools() {
    return List.of(grepTool(), globTool(), readTool());
  }

  private Tool grepTool() {
    return Tool.newBuilder()
        .withName("kb_grep")
        .withDescription(
            """
            Search the knowledge base for lines matching a Java regular expression.
            Returns matching lines in the format `path:lineNumber:lineContent`,
            one per line, capped at the configured maximum.
            """)
        .withParameter(
            ToolParameter.newBuilder()
                .withName("pattern")
                .withType(ParameterType.STRING)
                .withDescription("Java regular expression to match against each line")
                .withRequired(true)
                .build())
        .withParameter(
            ToolParameter.newBuilder()
                .withName("glob")
                .withType(ParameterType.STRING)
                .withDescription(
                    "Optional glob (e.g. `**/*.md`) restricting which files are scanned")
                .withRequired(false)
                .build())
        .withParameter(
            ToolParameter.newBuilder()
                .withName("max_results")
                .withType(ParameterType.INTEGER)
                .withDescription("Optional cap on the number of returned matches")
                .withRequired(false)
                .build())
        .withExecutor(this::executeGrep)
        .build();
  }

  private Tool globTool() {
    return Tool.newBuilder()
        .withName("kb_glob")
        .withDescription(
            """
            List paths in the knowledge base matching a glob (e.g. `**/*.md`).
            Returns paths relative to the knowledge root, one per line, capped at
            the configured maximum.
            """)
        .withParameter(
            ToolParameter.newBuilder()
                .withName("pattern")
                .withType(ParameterType.STRING)
                .withDescription("Glob pattern to match against paths relative to the root")
                .withRequired(true)
                .build())
        .withExecutor(this::executeGlob)
        .build();
  }

  private Tool readTool() {
    return Tool.newBuilder()
        .withName("kb_read")
        .withDescription(
            """
            Read a file from the knowledge base. Optional `start_line` and `end_line`
            (1-based, inclusive) return only that line range. Output is capped at the
            configured maximum bytes; if exceeded, a truncation marker is appended.
            """)
        .withParameter(
            ToolParameter.newBuilder()
                .withName("path")
                .withType(ParameterType.STRING)
                .withDescription("Path relative to the knowledge root")
                .withRequired(true)
                .build())
        .withParameter(
            ToolParameter.newBuilder()
                .withName("start_line")
                .withType(ParameterType.INTEGER)
                .withDescription("Optional first line to return (1-based, inclusive)")
                .withRequired(false)
                .build())
        .withParameter(
            ToolParameter.newBuilder()
                .withName("end_line")
                .withType(ParameterType.INTEGER)
                .withDescription("Optional last line to return (1-based, inclusive)")
                .withRequired(false)
                .build())
        .withExecutor(this::executeRead)
        .build();
  }

  private ToolResult executeGrep(Map<String, Object> args, ToolContext ctx) {
    ctx.cancellation().throwIfCancelled();
    var patternStr = stringArg(args, "pattern");
    if (patternStr == null) {
      return failure("Parameter 'pattern' is required and must be a string");
    }
    Pattern pattern;
    try {
      pattern = Pattern.compile(patternStr);
    } catch (PatternSyntaxException e) {
      return failure("Invalid regular expression: " + e.getDescription());
    }
    var globStr = stringArg(args, "glob");
    PathMatcher globMatcher = null;
    if (globStr != null) {
      try {
        globMatcher = compileGlob(globStr);
      } catch (IllegalArgumentException e) {
        return failure("Invalid glob: " + e.getMessage());
      }
    }
    var capArg = intArg(args, "max_results");
    var cap = capArg != null && capArg > 0 ? Math.min(capArg, maxGrepResults) : maxGrepResults;
    var deadline = System.nanoTime() + grepTimeout.toNanos();
    var hits = new ArrayList<String>();
    var visitor = new GrepVisitor(pattern, globMatcher, hits, cap, deadline);
    try {
      Files.walkFileTree(jail.root(), visitor);
    } catch (IOException e) {
      return failure("I/O error during grep: " + e.getMessage());
    }
    var sb = new StringBuilder();
    for (var hit : hits) {
      sb.append(hit).append('\n');
    }
    if (visitor.timedOut) {
      sb.append("[grep timed out after ").append(grepTimeout.toMillis()).append("ms]\n");
    } else if (hits.size() >= cap) {
      sb.append("[capped at ").append(cap).append(" matches]\n");
    } else if (hits.isEmpty()) {
      sb.append("(no matches)\n");
    }
    return success(sb.toString());
  }

  private ToolResult executeGlob(Map<String, Object> args, ToolContext ctx) {
    ctx.cancellation().throwIfCancelled();
    var globStr = stringArg(args, "pattern");
    if (globStr == null) {
      return failure("Parameter 'pattern' is required and must be a non-empty string");
    }
    PathMatcher matcher;
    try {
      matcher = compileGlob(globStr);
    } catch (IllegalArgumentException e) {
      return failure("Invalid glob: " + e.getMessage());
    }
    var hits = new ArrayList<String>();
    var visitor = new GlobVisitor(matcher, hits, maxGlobResults);
    try {
      Files.walkFileTree(jail.root(), visitor);
    } catch (IOException e) {
      return failure("I/O error during glob: " + e.getMessage());
    }
    var sb = new StringBuilder();
    for (var p : hits) {
      sb.append(p).append('\n');
    }
    if (hits.size() >= maxGlobResults) {
      sb.append("[capped at ").append(maxGlobResults).append(" results]\n");
    } else if (hits.isEmpty()) {
      sb.append("(no matches)\n");
    }
    return success(sb.toString());
  }

  private ToolResult executeRead(Map<String, Object> args, ToolContext ctx) {
    ctx.cancellation().throwIfCancelled();
    var pathStr = stringArg(args, "path");
    if (pathStr == null) {
      return failure("Parameter 'path' is required and must be a string");
    }
    var startLine = intArg(args, "start_line");
    var endLine = intArg(args, "end_line");
    if (startLine != null && startLine < 1) {
      return failure("'start_line' must be >= 1");
    }
    if (endLine != null && endLine < 1) {
      return failure("'end_line' must be >= 1");
    }
    if (startLine != null && endLine != null && endLine < startLine) {
      return failure("'end_line' must be >= 'start_line'");
    }
    Path resolved;
    try {
      resolved = jail.resolve(pathStr);
    } catch (PathJail.JailException e) {
      return failure(e.getMessage());
    }
    if (!Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
      return failure("File not found: " + pathStr);
    }
    if (Files.isSymbolicLink(resolved)) {
      return failure("Symlinks are not readable: " + pathStr);
    }
    if (!Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
      return failure("Not a regular file: " + pathStr);
    }
    try {
      jail.verifyReal(resolved);
    } catch (PathJail.JailException e) {
      return failure(e.getMessage());
    } catch (IOException e) {
      return failure("I/O error verifying path: " + e.getMessage());
    }
    long size;
    try {
      size = Files.size(resolved);
    } catch (IOException e) {
      return failure("I/O error reading size: " + e.getMessage());
    }
    if (size > maxFileSize) {
      return failure("File exceeds maximum size of %d bytes (was %d)".formatted(maxFileSize, size));
    }
    String text;
    try {
      text = readFile(resolved, startLine, endLine);
    } catch (IOException e) {
      return failure("I/O error reading file: " + e.getMessage());
    }
    return success(text);
  }

  private String readFile(Path file, Integer startLine, Integer endLine) throws IOException {
    var lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    var first = startLine != null ? Math.min(startLine, lines.size() + 1) : 1;
    var last = endLine != null ? Math.min(endLine, lines.size()) : lines.size();
    var sb = new StringBuilder();
    var truncated = false;
    for (var i = first - 1; i < last; i++) {
      var line = lines.get(i);
      if (sb.length() + line.length() + 1 > maxBytesPerRead) {
        truncated = true;
        break;
      }
      sb.append(line).append('\n');
    }
    if (truncated) {
      sb.append("[truncated: read exceeded ").append(maxBytesPerRead).append(" bytes]\n");
    }
    return sb.toString();
  }

  private ToolResult success(String text) {
    return ToolResult.success(secretRegistry.redactor().redact(text).text());
  }

  private ToolResult failure(String message) {
    return ToolResult.failure(secretRegistry.redactor().redact(message).text());
  }

  /**
   * Compile a glob pattern with Unix-tool semantics — {@code **&#47;*.md} matches markdown files
   * anywhere, <i>including</i> at the corpus root.
   *
   * <p>Java NIO's default {@link FileSystems#getPathMatcher} treats {@code **} as "zero or more
   * directory segments, with literal separator required after," so {@code **&#47;*.md} demands at
   * least one parent directory and silently skips root-level files. Every other glob system agents
   * are likely to have seen (ripgrep, fd, gitignore, Python pathlib, Go filepath/match) treats
   * {@code **&#47;*.md} as recursive-including-root — and the {@code kb_glob} tool description
   * itself uses {@code **&#47;*.md} as the example, so the model reaches for the pattern it can't
   * actually use. Live agent-session tests caught this on the first run.
   *
   * <p>Fix: for any pattern starting with {@code **&#47;}, also try the pattern with that prefix
   * stripped. A file matches if either form accepts it. Semantics preserved for non-{@code
   * **&#47;}-leading patterns; existing call sites unaffected.
   *
   * @param pattern glob pattern in JDK syntax; non-null
   * @return a matcher with the recursive-including-root fix applied
   * @throws IllegalArgumentException if either compiled form is malformed
   */
  static PathMatcher compileGlob(String pattern) {
    var primary = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
    if (pattern.startsWith("**/")) {
      var rootForm = FileSystems.getDefault().getPathMatcher("glob:" + pattern.substring(3));
      return path -> primary.matches(path) || rootForm.matches(path);
    }
    return primary;
  }

  private static String stringArg(Map<String, Object> args, String name) {
    return args.get(name) instanceof String s && !s.isEmpty() ? s : null;
  }

  private static Integer intArg(Map<String, Object> args, String name) {
    return args.get(name) instanceof Number n ? n.intValue() : null;
  }

  private static boolean isHidden(Path p) {
    return p.getFileName().toString().startsWith(".");
  }

  private static boolean isBinary(Path file) {
    try (var in = Files.newInputStream(file)) {
      return sniffBinary(in);
    } catch (IOException e) {
      return true;
    }
  }

  private static boolean sniffBinary(InputStream in) throws IOException {
    var buf = new byte[BINARY_SNIFF_BYTES];
    var n = in.readNBytes(buf, 0, buf.length);
    for (var i = 0; i < n; i++) {
      if (buf[i] == 0) {
        return true;
      }
    }
    return false;
  }

  private final class GrepVisitor extends SimpleFileVisitor<Path> {
    private final Pattern pattern;
    private final PathMatcher globMatcher;
    private final List<String> hits;
    private final int cap;
    private final long deadlineNanos;
    boolean timedOut;

    GrepVisitor(
        Pattern pattern, PathMatcher globMatcher, List<String> hits, int cap, long deadlineNanos) {
      this.pattern = pattern;
      this.globMatcher = globMatcher;
      this.hits = hits;
      this.cap = cap;
      this.deadlineNanos = deadlineNanos;
    }

    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
      if (!dir.equals(jail.root()) && skipHidden && isHidden(dir)) {
        return FileVisitResult.SKIP_SUBTREE;
      }
      if (pathFilter != null && !pathFilter.test(dir)) {
        return FileVisitResult.SKIP_SUBTREE;
      }
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
      if (System.nanoTime() > deadlineNanos) {
        timedOut = true;
        return FileVisitResult.TERMINATE;
      }
      if (attrs.isSymbolicLink() || !attrs.isRegularFile()) {
        return FileVisitResult.CONTINUE;
      }
      if (skipHidden && isHidden(file)) {
        return FileVisitResult.CONTINUE;
      }
      if (pathFilter != null && !pathFilter.test(file)) {
        return FileVisitResult.CONTINUE;
      }
      if (attrs.size() > maxFileSize) {
        return FileVisitResult.CONTINUE;
      }
      var rel = jail.root().relativize(file);
      if (globMatcher != null && !globMatcher.matches(rel)) {
        return FileVisitResult.CONTINUE;
      }
      if (isBinary(file)) {
        return FileVisitResult.CONTINUE;
      }
      try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
        var lineNo = new int[] {0};
        var iter = lines.iterator();
        while (iter.hasNext()) {
          if (System.nanoTime() > deadlineNanos) {
            timedOut = true;
            return FileVisitResult.TERMINATE;
          }
          var line = iter.next();
          lineNo[0]++;
          if (pattern.matcher(line).find()) {
            hits.add(rel + ":" + lineNo[0] + ":" + line);
            if (hits.size() >= cap) {
              return FileVisitResult.TERMINATE;
            }
          }
        }
      } catch (UncheckedIOException | IOException ignored) {
        // unreadable file — skip silently
      }
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exc) {
      return FileVisitResult.CONTINUE;
    }
  }

  private final class GlobVisitor extends SimpleFileVisitor<Path> {
    private final PathMatcher matcher;
    private final List<String> hits;
    private final int cap;

    GlobVisitor(PathMatcher matcher, List<String> hits, int cap) {
      this.matcher = matcher;
      this.hits = hits;
      this.cap = cap;
    }

    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
      if (!dir.equals(jail.root()) && skipHidden && isHidden(dir)) {
        return FileVisitResult.SKIP_SUBTREE;
      }
      if (pathFilter != null && !pathFilter.test(dir)) {
        return FileVisitResult.SKIP_SUBTREE;
      }
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
      if (attrs.isSymbolicLink() || !attrs.isRegularFile()) {
        return FileVisitResult.CONTINUE;
      }
      if (skipHidden && isHidden(file)) {
        return FileVisitResult.CONTINUE;
      }
      if (pathFilter != null && !pathFilter.test(file)) {
        return FileVisitResult.CONTINUE;
      }
      var rel = jail.root().relativize(file);
      if (matcher.matches(rel)) {
        hits.add(rel.toString());
        if (hits.size() >= cap) {
          return FileVisitResult.TERMINATE;
        }
      }
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exc) {
      return FileVisitResult.CONTINUE;
    }
  }

  /** Builder for {@link FilesystemKnowledge}. */
  public static final class Builder {
    private final Path root;
    private SecretRegistry secretRegistry;
    private int maxFileSize = DEFAULT_MAX_FILE_SIZE;
    private int maxBytesPerRead = DEFAULT_MAX_BYTES_PER_READ;
    private int maxGrepResults = DEFAULT_MAX_GREP_RESULTS;
    private int maxGlobResults = DEFAULT_MAX_GLOB_RESULTS;
    private Duration grepTimeout = DEFAULT_GREP_TIMEOUT;
    private boolean skipHidden = true;
    private Predicate<Path> pathFilter;

    private Builder(Path root) {
      this.root = root;
    }

    /**
     * Use the supplied registry so this knowledge base participates in cross-tool redaction. If not
     * called, a private empty registry is used and no redaction is performed.
     */
    public Builder withSecretRegistry(SecretRegistry registry) {
      this.secretRegistry = registry;
      return this;
    }

    /** Maximum size of any single file scanned by grep or returned by read. */
    public Builder withMaxFileSize(int bytes) {
      if (bytes < 1024) {
        throw new IllegalArgumentException("maxFileSize must be at least 1024");
      }
      this.maxFileSize = bytes;
      return this;
    }

    /** Cap on the number of bytes returned by a single {@code kb_read}. */
    public Builder withMaxBytesPerRead(int bytes) {
      if (bytes < 1024) {
        throw new IllegalArgumentException("maxBytesPerRead must be at least 1024");
      }
      this.maxBytesPerRead = bytes;
      return this;
    }

    /** Default cap on grep matches; the {@code max_results} arg may lower this further. */
    public Builder withMaxGrepResults(int n) {
      if (n < 1) {
        throw new IllegalArgumentException("maxGrepResults must be at least 1");
      }
      this.maxGrepResults = n;
      return this;
    }

    /** Cap on the number of paths returned by a single {@code kb_glob}. */
    public Builder withMaxGlobResults(int n) {
      if (n < 1) {
        throw new IllegalArgumentException("maxGlobResults must be at least 1");
      }
      this.maxGlobResults = n;
      return this;
    }

    /** Wall-clock deadline for any single grep call. */
    public Builder withGrepTimeout(Duration timeout) {
      if (timeout == null || timeout.isZero() || timeout.isNegative()) {
        throw new IllegalArgumentException("grepTimeout must be positive");
      }
      this.grepTimeout = timeout;
      return this;
    }

    /** Whether to skip dot-prefixed files and directories. Defaults to true. */
    public Builder withSkipHidden(boolean skip) {
      this.skipHidden = skip;
      return this;
    }

    /**
     * Optional additional filter applied to both directories and files during traversal. Returning
     * false on a directory skips its entire subtree.
     */
    public Builder withPathFilter(Predicate<Path> filter) {
      this.pathFilter = filter;
      return this;
    }

    public FilesystemKnowledge build() {
      PathJail builtJail;
      try {
        builtJail = new PathJail(root);
      } catch (IOException e) {
        throw new IllegalStateException("Failed to canonicalize root: " + root, e);
      }
      var registry = secretRegistry != null ? secretRegistry : new SecretRegistry();
      return new FilesystemKnowledge(this, builtJail, registry);
    }
  }
}
