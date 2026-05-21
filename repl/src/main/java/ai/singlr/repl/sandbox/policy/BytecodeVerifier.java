/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox.policy;

/**
 * Scans a single class's bytecode against a {@link SandboxPolicy} and throws {@link
 * SandboxPolicyException} on the first denied invocation found. Stateless and thread-safe by
 * convention — the {@link GuardedExecutionControl} seam may invoke it on any JShell-internal
 * thread.
 *
 * <p>{@link #forPolicy(SandboxPolicy)} returns {@link #NO_OP} when the policy is {@link
 * SandboxPolicy#isPermissive() permissive} (no scanning needed) and a {@link
 * PolicyBytecodeVerifier} otherwise — the real scanner built on the JDK Classfile API. See {@link
 * PolicyBytecodeVerifier} for the rule semantics and the set of instruction families scanned.
 */
public interface BytecodeVerifier {

  /**
   * Verify a single compiled class. Implementations must throw {@link SandboxPolicyException} (and
   * only that) when the bytes contain a denied operation, so the {@link GuardedExecutionControl}
   * seam can rewrap the failure into JShell's {@code ClassInstallException}. Returning normally
   * signals approval.
   *
   * @param internalName the loaded class's name in JShell's compilation context (e.g. {@code
   *     REPL.$JShell$5} for synthetic JShell wrapper classes) — used only for diagnostics
   * @param bytecodes raw classfile bytes as JShell handed them to the loader
   */
  void verify(String internalName, byte[] bytecodes);

  /**
   * A verifier that accepts every byte sequence. Returned by {@link #forPolicy(SandboxPolicy)} when
   * the policy is {@link SandboxPolicy#isPermissive() permissive} — there is nothing to scan for,
   * so the load path skips the Classfile parse entirely.
   */
  BytecodeVerifier NO_OP = (internalName, bytecodes) -> {};

  /**
   * Build a verifier for the given policy. Returns {@link #NO_OP} for {@link
   * SandboxPolicy#isPermissive() permissive} policies (no rules → no scanning needed) and a {@link
   * PolicyBytecodeVerifier} otherwise.
   */
  static BytecodeVerifier forPolicy(SandboxPolicy policy) {
    if (policy == null) {
      throw new IllegalArgumentException("policy must not be null");
    }
    if (policy.isPermissive()) {
      return NO_OP;
    }
    return new PolicyBytecodeVerifier(policy);
  }
}
