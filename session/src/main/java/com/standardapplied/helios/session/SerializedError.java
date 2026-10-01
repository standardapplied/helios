/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import ai.singlr.core.common.Strings;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A JSON-friendly snapshot of an error, carried through events, results, and audit records.
 *
 * <p>{@code SerializedError} is intentionally provider-agnostic and exception-class-agnostic so the
 * same shape survives serialization across SSE/WebSocket boundaries, persistence in audit logs, and
 * reconstruction in clients that do not have the originating exception class on the classpath.
 *
 * <p>The {@code cause} field captures Java's wrapped-exception chain: when a low-level {@code
 * IOException} is rewrapped as a {@code ReplException("failed to spawn", ioException)} and again as
 * a session-level error, the original {@code IOException} (and its message — "No such file or
 * directory: target/cron.jar") remains accessible via {@code error.cause().cause()}. Without this,
 * deployers see only the outermost wrapper's message and have no way to distinguish "binary not
 * found" from "permission denied" from "disk full" — the exact bug that motivated 2.5.2.
 *
 * @param kind a stable category for the error — typically the throwable's class name, or a domain
 *     tag like {@code "interrupted"}, {@code "timeout"}, {@code "hook-block"}
 * @param message a human-readable description; never null, may be empty (some exceptions carry no
 *     message)
 * @param stackTrace zero or more frames captured at the point of failure; defensively copied to an
 *     immutable list
 * @param cause the wrapped throwable's serialized form, or {@code null} when this error has no
 *     wrapped cause. Recursive: {@code cause.cause()} continues the chain
 */
public record SerializedError(
    String kind, String message, List<String> stackTrace, SerializedError cause) {

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if {@code kind} or {@code message} is null
   * @throws IllegalArgumentException if {@code kind} is blank
   */
  public SerializedError {
    Objects.requireNonNull(kind, "kind must not be null");
    if (Strings.isBlank(kind)) {
      throw new IllegalArgumentException("kind must not be blank");
    }
    Objects.requireNonNull(message, "message must not be null");
    stackTrace = stackTrace == null ? List.of() : List.copyOf(stackTrace);
  }

  /**
   * Build a {@code SerializedError} from a category and message, with no stack trace and no cause.
   *
   * @param kind a stable category tag, non-blank
   * @param message a human-readable description; non-null, may be empty
   * @return a new {@code SerializedError}
   */
  public static SerializedError of(String kind, String message) {
    return new SerializedError(kind, message, List.of(), null);
  }

  /**
   * Build a {@code SerializedError} from a throwable, walking its cause chain. The outer error
   * captures the throwable's {@link Throwable#getClass() class name}, {@link Throwable#getMessage()
   * message}, and {@link Throwable#getStackTrace() stack trace}; each {@link Throwable#getCause()
   * cause} is recursively serialised into the {@code cause} field. Self-referential and cyclic
   * cause chains are broken at a depth of 16 frames to bound output size.
   *
   * @param throwable the throwable to capture
   * @return a new {@code SerializedError}
   * @throws NullPointerException if {@code throwable} is null
   */
  public static SerializedError of(Throwable throwable) {
    Objects.requireNonNull(throwable, "throwable must not be null");
    return ofChain(throwable, 0);
  }

  private static final int MAX_CAUSE_DEPTH = 16;

  private static SerializedError ofChain(Throwable t, int depth) {
    var frames = Arrays.stream(t.getStackTrace()).map(StackTraceElement::toString).toList();
    var msg = t.getMessage();
    var cause = t.getCause();
    var serializedCause =
        (cause == null || cause == t || depth >= MAX_CAUSE_DEPTH)
            ? null
            : ofChain(cause, depth + 1);
    return new SerializedError(
        t.getClass().getName(), msg == null ? "" : msg, frames, serializedCause);
  }

  /**
   * The wrapped error in this chain, if any.
   *
   * @return an optional view of {@link #cause()}; empty when this error has no wrapped cause
   */
  public Optional<SerializedError> causeOpt() {
    return Optional.ofNullable(cause);
  }

  /**
   * Return a copy of this error with the stack-trace frame list stripped recursively across the
   * entire cause chain. Kind and message are preserved so consumers still see the error category
   * and human-readable description; only the internal class-and-file-line frames are removed.
   *
   * <p>Used at trust boundaries (HTTP responses, SSE event serialisation) so library-internal
   * structure does not leak to clients.
   *
   * @return a {@code SerializedError} with empty stack traces at every level of the chain
   */
  public SerializedError withoutStackTrace() {
    var strippedCause = cause == null ? null : cause.withoutStackTrace();
    if (stackTrace.isEmpty() && strippedCause == cause) {
      return this;
    }
    return new SerializedError(kind, message, List.of(), strippedCause);
  }
}
