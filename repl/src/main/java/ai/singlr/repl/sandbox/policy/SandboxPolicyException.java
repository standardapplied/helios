/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox.policy;

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

  private final String deniedOwner;
  private final String deniedMember;
  private final String rule;

  /**
   * @param deniedOwner internal-form class name of the denied API (e.g. {@code
   *     java/lang/ProcessBuilder}), or {@code null} for rules not tied to a specific owner
   * @param deniedMember method or field name that triggered the denial, or {@code null}
   * @param rule short label naming the policy rule that fired (e.g. {@code
   *     "deniedClasses:java.lang.ProcessBuilder"}, {@code "denyReflection"})
   */
  public SandboxPolicyException(String deniedOwner, String deniedMember, String rule) {
    super(buildMessage(deniedOwner, deniedMember, rule));
    this.deniedOwner = deniedOwner;
    this.deniedMember = deniedMember;
    this.rule = rule;
  }

  public String deniedOwner() {
    return deniedOwner;
  }

  public String deniedMember() {
    return deniedMember;
  }

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
