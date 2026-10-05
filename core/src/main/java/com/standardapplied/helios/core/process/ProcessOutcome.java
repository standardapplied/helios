/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.process;

import java.time.Duration;

/**
 * What a {@link BoundedProcess} produced once it exited or was killed. The output is raw: the
 * caller decodes and redacts it.
 *
 * @param exitCode the process exit code, or {@code -1} when it was killed on timeout
 * @param stdout captured stdout, ending in a truncation marker when it exceeded the cap
 * @param stderr captured stderr, ending in a truncation marker when it exceeded the cap
 * @param timedOut whether the process was killed because it outlived its timeout
 * @param truncated whether stdout or stderr exceeded the cap and was clipped
 * @param elapsed time from start until the process and its output streams finished
 */
public record ProcessOutcome(
    int exitCode,
    byte[] stdout,
    byte[] stderr,
    boolean timedOut,
    boolean truncated,
    Duration elapsed) {}
