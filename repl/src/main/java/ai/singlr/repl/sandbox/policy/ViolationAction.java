/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox.policy;

/**
 * How {@link BytecodeVerifier} reports a {@link SandboxPolicy} violation when verifying snippet
 * bytecode.
 */
public enum ViolationAction {

  /**
   * Throw {@link SandboxPolicyException} immediately. The exception bubbles up through JShell's
   * load path as a normal class-install failure; the sandbox surfaces it to the model as eval
   * stderr, preserving the model's self-correction path.
   */
  THROW
}
