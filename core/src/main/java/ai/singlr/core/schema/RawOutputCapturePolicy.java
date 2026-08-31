/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.schema;

/** Controls whether structured-output exceptions retain the model's raw response text. */
public enum RawOutputCapturePolicy {
  /** Preserve raw model output for debugging. Unsuitable for privacy-sensitive workloads. */
  ENABLED,

  /** Retain correction details but discard raw model output from exception objects. */
  DISABLED;

  String retain(String rawOutput) {
    return this == ENABLED ? rawOutput : null;
  }
}
