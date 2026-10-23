/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.process;

import java.time.Duration;

/**
 * What a {@link BoundedProcess} produced once it exited or was killed. The output is raw apart from
 * a cut stream losing the start of a secret the cap cut through: the caller decodes and redacts it.
 *
 * @param exitCode the process exit code, or {@code -1} when it was killed on timeout or
 *     cancellation
 * @param stdout captured stdout, ending in a truncation marker when it exceeded the cap or was
 *     still open after the grace
 * @param stderr captured stderr, ending in a truncation marker when it exceeded the cap or was
 *     still open after the grace
 * @param timedOut whether the process was killed because it outlived its timeout
 * @param stdoutTruncated whether stdout exceeded the cap or was still open after the grace
 * @param stderrTruncated whether stderr exceeded the cap or was still open after the grace
 * @param elapsed time from start until the process and its output streams finished
 */
public record ProcessOutcome(
    int exitCode,
    byte[] stdout,
    byte[] stderr,
    boolean timedOut,
    boolean stdoutTruncated,
    boolean stderrTruncated,
    Duration elapsed) {

  /** Whether stdout or stderr exceeded the cap or was still open after the grace. */
  public boolean truncated() {
    return stdoutTruncated || stderrTruncated;
  }
}
