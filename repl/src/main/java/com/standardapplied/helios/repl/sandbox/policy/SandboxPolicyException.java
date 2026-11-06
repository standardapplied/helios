/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.repl.sandbox.policy;

/**
 * Thrown by {@link BytecodeVerifier} when a snippet's bytecode invokes an API denied by the active
 * {@link SandboxPolicy}. Carries the offending owner ({@code java/lang/ProcessBuilder} form), the
 * method name, and a human-readable rule label so the model receives a useful traceback when the
 * exception surfaces through JShell as an eval failure.
 *
 * <p>This is a {@link RuntimeException} on purpose: JShell's {@code load} signature is
 * checked-exception-narrow and wrapping into one of its declared exceptions would lose the
 * fault-localisation fields. The sandbox catches this at the {@link GuardedExecutionControl} seam
 * and rewraps it as a {@code ClassInstallException} so JShell's bookkeeping stays consistent.
 */
public final class SandboxPolicyException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Internal-form name of the denied class, or {@code null} when the rule names no owner. */
  private final String deniedOwner;

  /** Name of the denied method or field, or {@code null} when the rule names no member. */
  private final String deniedMember;

  /** Label of the policy rule that fired, or {@code null} when none was given. */
  private final String rule;

  /**
   * Creates the exception with a message of the form {@code Sandbox policy denied
   * java.lang.ProcessBuilder#start [rule: ...]}, the owner shown in dotted form.
   *
   * @param deniedOwner internal-form class name of the denied API (e.g. {@code
   *     java/lang/ProcessBuilder}), or {@code null} for rules not tied to a specific owner
   * @param deniedMember method or field name that triggered the denial, or {@code null}
   * @param rule short label naming the policy rule that fired (e.g. {@code
   *     "deniedClasses:java.lang.ProcessBuilder"}, {@code "denyReflection"}), or {@code null} to
   *     leave the rule out of the message
   */
  public SandboxPolicyException(String deniedOwner, String deniedMember, String rule) {
    super(buildMessage(deniedOwner, deniedMember, rule));
    this.deniedOwner = deniedOwner;
    this.deniedMember = deniedMember;
    this.rule = rule;
  }

  /**
   * Returns the internal-form name of the denied class, such as {@code java/lang/ProcessBuilder}.
   *
   * @return the denied owner, or {@code null} when the rule is not tied to a specific class
   */
  public String deniedOwner() {
    return deniedOwner;
  }

  /**
   * Returns the name of the method or field whose reference triggered the denial.
   *
   * @return the denied member, or {@code null} when the denial names no member
   */
  public String deniedMember() {
    return deniedMember;
  }

  /**
   * Returns the label of the policy rule that fired, such as {@code "denyReflection"} or {@code
   * "allowedPackages-default-deny"}.
   *
   * @return the rule label, or {@code null} when none was given
   */
  public String rule() {
    return rule;
  }

  private static String buildMessage(String owner, String member, String rule) {
    var sb = new StringBuilder("Sandbox policy denied ");
    if (owner != null) {
      sb.append(owner.replace('/', '.'));
      if (member != null) {
        sb.append('#').append(member);
      }
    } else if (member != null) {
      sb.append(member);
    } else {
      sb.append("operation");
    }
    if (rule != null) {
      sb.append(" [rule: ").append(rule).append(']');
    }
    return sb.toString();
  }
}
