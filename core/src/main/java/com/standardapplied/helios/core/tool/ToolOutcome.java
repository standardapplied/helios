/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.tool;

import com.standardapplied.helios.core.common.Strings;
import java.time.Duration;
import java.util.Objects;

/**
 * What happened to one tool call: it ran and succeeded or failed, it threw, it timed out, it timed
 * out and did not stop, a hook rejected it before it ran, or no tool by its name exists. A consumer
 * counts or routes on this type, never on the model-facing text in {@link ToolResult#output()}.
 */
public sealed interface ToolOutcome {

  /** The tool ran and reported success. */
  record Succeeded() implements ToolOutcome {}

  /** The tool ran and reported failure. */
  record Failed() implements ToolOutcome {}

  /**
   * The tool, or the dispatch of it, threw.
   *
   * @param exceptionType the throwable's fully qualified class name; non-blank
   */
  record Threw(String exceptionType) implements ToolOutcome {

    public Threw {
      requireNonBlank(exceptionType, "exceptionType");
    }
  }

  /**
   * The call exceeded its time limit.
   *
   * @param limit the limit in force for the call; positive
   */
  record TimedOut(Duration limit) implements ToolOutcome {

    public TimedOut {
      requirePositive(limit);
    }
  }

  /**
   * The call exceeded its time limit while its tool was running, and the tool did not stop when
   * cancelled and interrupted. It may still be running, and its effects may still land; the
   * dispatcher keeps the call's category capacity charged until it ends.
   *
   * @param limit the limit in force for the call; positive
   */
  record Unknown(Duration limit) implements ToolOutcome {

    public Unknown {
      requirePositive(limit);
    }
  }

  /**
   * A hook refused or replaced the call before it ran.
   *
   * @param by the name of the hook that rejected the call; non-blank
   * @param reason the hook's reason; non-blank
   */
  record Rejected(String by, String reason) implements ToolOutcome {

    public Rejected {
      requireNonBlank(by, "by");
      requireNonBlank(reason, "reason");
    }
  }

  /** No tool with that name is registered. */
  record NotFound() implements ToolOutcome {}

  private static void requirePositive(Duration limit) {
    Objects.requireNonNull(limit, "limit must not be null");
    if (limit.isNegative() || limit.isZero()) {
      throw new IllegalArgumentException("limit must be positive, got " + limit);
    }
  }

  private static void requireNonBlank(String value, String name) {
    Objects.requireNonNull(value, name + " must not be null");
    if (Strings.isBlank(value)) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
