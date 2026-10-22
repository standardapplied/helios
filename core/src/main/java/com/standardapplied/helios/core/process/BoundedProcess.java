/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.process;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.runtime.CancellationToken;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * One child process started from an explicit argv and environment, never through a shell, with its
 * stdout and stderr captured into bounded buffers, a timeout that kills the process and its
 * descendants, and a per-call working directory removed on {@link #close()}. A buffer cut at its
 * cap keeps no part of a registered secret the cut went through. A descendant that detached from
 * the child before the child exited cannot be found portably: it is not killed, and its output
 * after {@link #GRACE} is dropped.
 *
 * <p>The child inherits nothing from the JVM's environment; it sees exactly the variables the
 * builder was given. Its stdin is left open for the caller, who closes it or feeds it through
 * {@link #stdin()}. Use try-with-resources so the working directory is always removed:
 *
 * <pre>{@code
 * try (var process = BoundedProcess.newBuilder(argv).withEnvironment(env).start()) {
 *   process.stdin().close();
 *   var outcome = process.await(cancellation);
 * }
 * }</pre>
 */
public final class BoundedProcess implements AutoCloseable {

  /**
   * How long a killed process tree is given to end before it is killed forcibly, and how long each
   * output stream is given to end once the child has exited.
   */
  public static final Duration GRACE = Duration.ofSeconds(2);

  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
  private static final int DEFAULT_MAX_OUTPUT_BYTES = 50_000;
  private static final String DEFAULT_TEMP_DIRECTORY_PREFIX = "helios-process-";

  private final Process process;
  private final Path ownedDirectory;
  private final Duration timeout;
  private final SecretRegistry secrets;
  private final long startNanos;
  private final ProcessTree tree;
  private final BoundedSink stdoutSink;
  private final BoundedSink stderrSink;

  private BoundedProcess(Process process, Path ownedDirectory, Builder builder, long startNanos) {
    this.process = process;
    this.ownedDirectory = ownedDirectory;
    this.timeout = builder.timeout;
    this.secrets = builder.secrets;
    this.startNanos = startNanos;
    this.tree = new ProcessTree(process);
    this.stdoutSink = BoundedSink.draining(process.getInputStream(), builder.maxOutputBytes);
    this.stderrSink = BoundedSink.draining(process.getErrorStream(), builder.maxOutputBytes);
  }

  /**
   * Start a builder for a process running {@code argv}.
   *
   * @param argv the binary followed by its arguments; non-empty, no null entries
   * @return a fresh builder
   * @throws IllegalArgumentException if {@code argv} is empty
   */
  public static Builder newBuilder(List<String> argv) {
    return new Builder(argv);
  }

  /** The running child, for callers that track in-flight processes. */
  public Process process() {
    return process;
  }

  /** The child's stdin. The caller closes it, after writing to it or not. */
  public OutputStream stdin() {
    return process.getOutputStream();
  }

  /** Ask the child and every descendant to terminate. Safe to call from any thread, repeatedly. */
  public void kill() {
    tree.kill();
  }

  /**
   * Wait for the child to exit, then up to {@link #GRACE} for each output stream to end. The child
   * and its descendants are killed when the timeout elapses or {@code cancellation} fires, and on
   * every exception while waiting; a killed tree still alive after {@link #GRACE} is killed
   * forcibly. A stream cut at the cap, or still open after the grace, ends in a truncation marker
   * and loses its trailing part of a secret registered in the builder's registry.
   *
   * @param cancellation the call's token; firing it kills the child
   * @return the exit code, or {@code -1} for a child this call killed, the captured output and
   *     timing
   * @throws InterruptedException if the calling thread is interrupted while waiting, once the child
   *     and its descendants are killed and reaped; the interrupt status is set again
   */
  public ProcessOutcome await(CancellationToken cancellation) throws InterruptedException {
    Objects.requireNonNull(cancellation, "cancellation");
    var ended = new CountDownLatch(1);
    process.onExit().thenRun(ended::countDown);
    var registration =
        cancellation.onCancel(
            () -> {
              kill();
              ended.countDown();
            });
    var settled = false;
    try {
      var timedOut = !ended.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
      var killed = timedOut || cancellation.isCancelled();
      if (killed) {
        tree.killAndReap(GRACE);
      }
      var deadline = System.nanoTime() + GRACE.toNanos();
      stdoutSink.awaitEnd(deadline);
      stderrSink.awaitEnd(deadline);
      settled = true;
      return outcome(killed ? -1 : process.exitValue(), timedOut);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw e;
    } finally {
      registration.remove();
      if (!settled) {
        tree.killAndReap(GRACE);
      }
    }
  }

  private ProcessOutcome outcome(int exitCode, boolean timedOut) {
    var redactor = secrets.redactor();
    return new ProcessOutcome(
        exitCode,
        stdoutSink.bytes(redactor),
        stderrSink.bytes(redactor),
        timedOut,
        stdoutSink.truncated(),
        stderrSink.truncated(),
        Duration.ofNanos(System.nanoTime() - startNanos));
  }

  /** Remove the per-call working directory, if this process created one. */
  @Override
  public void close() {
    if (ownedDirectory != null) {
      deleteRecursively(ownedDirectory);
    }
  }

  /**
   * Best-effort: whatever the child left behind, cleanup never throws, so it cannot cost the caller
   * the outcome. {@code Files.walk} reports a directory it cannot read as an {@link
   * UncheckedIOException} while iterating.
   */
  private static void deleteRecursively(Path root) {
    try (var stream = Files.walk(root)) {
      stream.sorted(Comparator.reverseOrder()).forEach(BoundedProcess::deleteQuietly);
    } catch (IOException | UncheckedIOException ignored) {
      // Best-effort cleanup.
    }
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // Best-effort cleanup.
    }
  }

  /** Builder for {@link BoundedProcess}; {@link #start()} launches the child. */
  public static final class Builder {

    private final List<String> argv;
    private Map<String, String> environment = Map.of();
    private Path workingDirectory;
    private String tempDirectoryPrefix = DEFAULT_TEMP_DIRECTORY_PREFIX;
    private Duration timeout = DEFAULT_TIMEOUT;
    private int maxOutputBytes = DEFAULT_MAX_OUTPUT_BYTES;
    private SecretRegistry secrets = new SecretRegistry();

    private Builder(List<String> argv) {
      this.argv = List.copyOf(argv);
      if (this.argv.isEmpty()) {
        throw new IllegalArgumentException("argv must name the binary to run");
      }
    }

    /**
     * The child's whole environment, in insertion order; nothing is inherited from the JVM.
     * Defaults to empty.
     */
    public Builder withEnvironment(Map<String, String> environment) {
      this.environment = new LinkedHashMap<>(environment);
      return this;
    }

    /**
     * Run in {@code directory}, which outlives the process. When unset, each start creates a fresh
     * temporary directory that {@link BoundedProcess#close()} removes.
     */
    public Builder withWorkingDirectory(Path directory) {
      this.workingDirectory = directory;
      return this;
    }

    /** Name prefix of the per-call temporary directory. Defaults to {@code helios-process-}. */
    public Builder withTempDirectoryPrefix(String prefix) {
      this.tempDirectoryPrefix = Objects.requireNonNull(prefix, "prefix");
      return this;
    }

    /**
     * How long {@link BoundedProcess#await(CancellationToken)} lets the child run. Defaults to 30
     * seconds.
     */
    public Builder withTimeout(Duration timeout) {
      if (timeout == null || timeout.isZero() || timeout.isNegative()) {
        throw new IllegalArgumentException("Timeout must be positive");
      }
      this.timeout = timeout;
      return this;
    }

    /** Cap on captured stdout and on captured stderr, each in bytes. Defaults to 50,000. */
    public Builder withMaxOutputBytes(int bytes) {
      if (bytes < 1) {
        throw new IllegalArgumentException("maxOutputBytes must be positive");
      }
      this.maxOutputBytes = bytes;
      return this;
    }

    /**
     * The registry a stream cut at the cap is trimmed against, so that it keeps no part of a secret
     * the cut went through. Defaults to an empty registry, which trims nothing.
     */
    public Builder withSecretRegistry(SecretRegistry secrets) {
      this.secrets = Objects.requireNonNull(secrets, "secrets");
      return this;
    }

    /**
     * Create the working directory if none was set, then start the child and begin capturing its
     * output.
     *
     * @return the running process
     * @throws IOException if the directory cannot be created or the child cannot be started; a
     *     directory created for this call is removed first
     */
    public BoundedProcess start() throws IOException {
      var owned = workingDirectory == null ? Files.createTempDirectory(tempDirectoryPrefix) : null;
      var started = false;
      try {
        var pb = new ProcessBuilder(argv);
        pb.environment().clear();
        pb.environment().putAll(environment);
        pb.directory((owned != null ? owned : workingDirectory).toFile());
        pb.redirectInput(ProcessBuilder.Redirect.PIPE);
        var startNanos = System.nanoTime();
        var process = new BoundedProcess(pb.start(), owned, this, startNanos);
        started = true;
        return process;
      } finally {
        if (!started && owned != null) {
          deleteRecursively(owned);
        }
      }
    }
  }
}
