/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import ai.singlr.core.common.Strings;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A JSON-friendly snapshot of an error, carried through events, results, and audit records.
 *
 * <p>{@code SerializedError} is intentionally provider-agnostic and exception-class-agnostic so the
 * same shape survives serialization across SSE/WebSocket boundaries, persistence in audit logs, and
 * reconstruction in clients that do not have the originating exception class on the classpath.
 *
 * @param kind a stable category for the error — typically the throwable's class name, or a domain
 *     tag like {@code "interrupted"}, {@code "timeout"}, {@code "hook-block"}
 * @param message a human-readable description; never null, may be empty (some exceptions carry no
 *     message)
 * @param stackTrace zero or more frames captured at the point of failure; defensively copied to an
 *     immutable list
 */
public record SerializedError(String kind, String message, List<String> stackTrace) {

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
   * Build a {@code SerializedError} from a category and message, with no stack trace.
   *
   * @param kind a stable category tag, non-blank
   * @param message a human-readable description; non-null, may be empty
   * @return a new {@code SerializedError}
   */
  public static SerializedError of(String kind, String message) {
    return new SerializedError(kind, message, List.of());
  }

  /**
   * Build a {@code SerializedError} from a throwable. The {@code kind} is the throwable's
   * fully-qualified class name; the {@code message} is the throwable's message (or empty string if
   * null); the {@code stackTrace} is the throwable's stack frames rendered via {@link
   * StackTraceElement#toString()}.
   *
   * @param throwable the throwable to capture
   * @return a new {@code SerializedError}
   * @throws NullPointerException if {@code throwable} is null
   */
  public static SerializedError of(Throwable throwable) {
    Objects.requireNonNull(throwable, "throwable must not be null");
    var frames = Arrays.stream(throwable.getStackTrace()).map(StackTraceElement::toString).toList();
    var msg = throwable.getMessage();
    return new SerializedError(throwable.getClass().getName(), msg == null ? "" : msg, frames);
  }

  /**
   * Return a copy of this error with the stack-trace frame list stripped. Kind and message are
   * preserved so consumers still see the error category and human-readable description; only the
   * internal class-and-file-line frames are removed.
   *
   * <p>Used at trust boundaries (HTTP responses, SSE event serialisation) so library-internal
   * structure does not leak to clients. When this error has no frames to begin with, returns {@code
   * this} so the no-op path costs nothing.
   *
   * @return a {@code SerializedError} with the same kind and message and an empty stack trace
   */
  public SerializedError withoutStackTrace() {
    return stackTrace.isEmpty() ? this : new SerializedError(kind, message, List.of());
  }
}
