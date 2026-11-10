/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import com.standardapplied.helios.core.common.SecretRegistry;
import io.opentelemetry.api.trace.Span;
import java.util.Optional;

/**
 * A run's error, null for a {@code Success}, and its {@code error.type}: the first host {@link
 * Error} the session recorded, else the crash, each as its redacted {@code kind: message}, else the
 * terminal's simple name, with the {@code kind: message} of the error it carries.
 */
record SpanFailure(String error, String type) {

  static SpanFailure of(
      Optional<Error> host,
      RuntimeException crash,
      ResultMessage terminal,
      SecretRegistry secrets) {
    Throwable thrown = host.isPresent() ? host.get() : crash;
    if (thrown != null) {
      return new SpanFailure(described(thrown, secrets), thrown.getClass().getName());
    }
    var name = terminal.getClass().getSimpleName();
    return switch (terminal) {
      case ResultMessage.Success _ -> new SpanFailure(null, null);
      case ResultMessage.ErrorDuringExecution failed ->
          new SpanFailure(name + ": " + described(failed.error()), name);
      case ResultMessage.ErrorTransientStream failed ->
          new SpanFailure(name + ": " + described(failed.error()), name);
      default -> new SpanFailure(name, name);
    };
  }

  /** End {@code span}, failed with this error unless there is none. */
  void end(Span span) {
    if (error != null) {
      RunSpans.fail(span, error, type);
    }
    span.end();
  }

  /**
   * {@code thrown}'s redacted {@code kind: message}, or its class name when that cannot be read.
   */
  private static String described(Throwable thrown, SecretRegistry secrets) {
    try {
      return described(SerializedError.of(thrown).redactedBy(secrets.redactor()));
    } catch (RuntimeException unreadable) {
      return thrown.getClass().getName();
    }
  }

  private static String described(SerializedError error) {
    return error.kind() + ": " + error.message();
  }
}
