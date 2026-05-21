/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox.policy;

import java.util.Set;

/**
 * Declarative policy describing which APIs a JShell snippet may invoke inside the Helios sandbox.
 *
 * <p>The policy is consumed by {@link BytecodeVerifier} inside the sandbox subprocess, which scans
 * every compiled snippet through {@link GuardedExecutionControl} before the JVM links it. The
 * verifier rejects denied invocations by throwing a {@link SandboxPolicyException} carrying the
 * offending class, method, and rule — that exception surfaces to the model as a normal JShell eval
 * failure ({@code ToolResult.success} with stderr populated), preserving the model's
 * self-correction path.
 *
 * <p><strong>Defense in depth, not the perimeter.</strong> Policy enforcement raises the bar
 * against honest mistakes and probing snippets but is not a substitute for OS-level isolation
 * (Incus / namespaces / per-UID separation). A sufficiently motivated adversary inside the
 * subprocess can still side-step in-JVM controls; the only authoritative boundary is the host
 * kernel. Deployers running untrusted snippet payloads must arrange external isolation around the
 * Helios host process.
 *
 * @param allowedPackages opt-in allow-list of JDK package prefixes. When non-empty, the policy
 *     flips from "default-allow" to "default-deny" for JDK-owned classes (those under {@code
 *     java/}, {@code javax/}, {@code jdk/}, {@code sun/}, {@code com/sun/}): a JDK class is only
 *     callable if its package matches one of the allowed prefixes. Non-JDK owners (the snippet's
 *     own compiled classes — {@code REPL.$JShell$N}, user helper classes, third-party JARs on the
 *     snippet's classpath) are always allowed regardless of this list, because they're the very
 *     code being verified. Deny rules ({@code deniedClasses}, {@code deniedPackages}, the
 *     categorical flags) still apply as overrides — deny wins over allow. Empty set means
 *     allow-list mode is OFF (current pure deny-list behaviour).
 * @param deniedClasses fully-qualified class names the snippet must not invoke methods on (e.g.
 *     {@code "java.lang.ProcessBuilder"}). Empty set means no class-level denies.
 * @param deniedPackages package prefixes whose classes are denied transitively (e.g. {@code
 *     "java.net.http"} denies every class in that package and any sub-packages). Empty set means no
 *     package-level denies.
 * @param denyReflection if {@code true}, denies the entire reflection family — {@code
 *     Class.forName}, {@code Method.invoke}, {@link java.lang.invoke.MethodHandles}, {@link
 *     java.lang.invoke.MethodHandles.Lookup#findVirtual} and friends, {@code setAccessible}. This
 *     is a single switch because partial reflection denial is bypassable by composing the surviving
 *     primitives.
 * @param denyNativeAccess if {@code true}, denies {@code java.lang.foreign.*} (Panama FFI) and
 *     {@code java.lang.System.loadLibrary} / {@code load}. Native invocation is the principal
 *     escape hatch around any in-JVM controls.
 * @param denyDynamicClassDefinition if {@code true}, denies primitives that materialise classes
 *     from bytecode at runtime — {@link java.lang.invoke.MethodHandles.Lookup#defineClass}, {@code
 *     defineHiddenClass}, and {@code ClassLoader.defineClass}. Without this, a snippet can
 *     synthesise bytes that bypass the verifier seam by defining classes in a loader the verifier
 *     never sees.
 * @param onViolation how the verifier reports a violation. {@link ViolationAction#THROW} is the
 *     only mode in PR 1; a future {@code ASK_HOST} mode may route through a {@code QuestionGateway}
 *     when one is wired.
 */
public record SandboxPolicy(
    Set<String> allowedPackages,
    Set<String> deniedClasses,
    Set<String> deniedPackages,
    boolean denyReflection,
    boolean denyNativeAccess,
    boolean denyDynamicClassDefinition,
    ViolationAction onViolation) {

  public SandboxPolicy {
    if (allowedPackages == null) {
      throw new IllegalArgumentException(
          "allowedPackages must not be null (use empty set instead)");
    }
    if (deniedClasses == null) {
      throw new IllegalArgumentException("deniedClasses must not be null (use empty set instead)");
    }
    if (deniedPackages == null) {
      throw new IllegalArgumentException("deniedPackages must not be null (use empty set instead)");
    }
    if (onViolation == null) {
      throw new IllegalArgumentException("onViolation must not be null");
    }
    for (var name : allowedPackages) {
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("allowedPackages entries must be non-blank");
      }
    }
    for (var name : deniedClasses) {
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("deniedClasses entries must be non-blank");
      }
    }
    for (var name : deniedPackages) {
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("deniedPackages entries must be non-blank");
      }
    }
    allowedPackages = Set.copyOf(allowedPackages);
    deniedClasses = Set.copyOf(deniedClasses);
    deniedPackages = Set.copyOf(deniedPackages);
  }

  /**
   * The default permissive policy — denies nothing. Equivalent to running with no policy layer at
   * all. Use this for development and for any workload where the deployer has not yet articulated a
   * specific enforcement requirement; switch to a stricter preset (or a custom policy) when you
   * have one.
   */
  public static SandboxPolicy permissive() {
    return new SandboxPolicy(
        Set.of(), Set.of(), Set.of(), false, false, false, ViolationAction.THROW);
  }

  /**
   * Curated preset: "no egress". Lets the snippet read inputs, do compute, manipulate collections,
   * format numbers and times, write to stdout / stderr — but blocks every path out of the sandbox:
   * no process spawn, no network, no file IO, no reflection escape, no native code, no dynamic
   * class definition.
   *
   * <p>Composition:
   *
   * <ul>
   *   <li>{@code allowedPackages} = {@code java.lang}, {@code java.util}, {@code java.util.stream},
   *       {@code java.util.function}, {@code java.util.regex}, {@code java.util.concurrent.atomic},
   *       {@code java.math}, {@code java.time}, {@code java.time.format}, {@code
   *       java.time.temporal}, {@code java.text}, {@code java.io} (for {@code System.out.println},
   *       formatted output, in-memory streams) — the safe compute and stdio surface
   *   <li>{@code deniedClasses} = the dangerous classes inside the allowed packages: {@code
   *       java.lang.ProcessBuilder}, {@code java.lang.Runtime}, {@code java.lang.Thread}, {@code
   *       java.lang.ThreadGroup}, plus the file / serialization classes in {@code java.io} ({@code
   *       FileReader}, {@code FileWriter}, {@code FileInputStream}, {@code FileOutputStream},
   *       {@code RandomAccessFile}, {@code ObjectInputStream}, {@code ObjectOutputStream})
   *   <li>{@code denyReflection}, {@code denyNativeAccess}, {@code denyDynamicClassDefinition} all
   *       enabled — categorical escape hatches closed
   * </ul>
   *
   * <p>Deployers who need a single API call to lock the agent into "data crunching with no egress"
   * reach for this preset and customise from there. Returns a fresh {@link SandboxPolicy}; pass
   * directly to {@link
   * ai.singlr.repl.sandbox.JvmSandboxConfig.Builder#withSandboxPolicy(SandboxPolicy)} or compose by
   * round-tripping through a Builder.
   */
  public static SandboxPolicy noEgress() {
    return new SandboxPolicy(
        Set.of(
            "java.lang",
            "java.util",
            "java.util.stream",
            "java.util.function",
            "java.util.regex",
            "java.util.concurrent.atomic",
            "java.math",
            "java.time",
            "java.time.format",
            "java.time.temporal",
            "java.text",
            "java.io"),
        Set.of(
            "java.lang.ProcessBuilder",
            "java.lang.Runtime",
            "java.lang.Thread",
            "java.lang.ThreadGroup",
            "java.io.FileReader",
            "java.io.FileWriter",
            "java.io.FileInputStream",
            "java.io.FileOutputStream",
            "java.io.RandomAccessFile",
            "java.io.ObjectInputStream",
            "java.io.ObjectOutputStream"),
        Set.of(),
        true,
        true,
        true,
        ViolationAction.THROW);
  }

  /**
   * Whether this policy enforces nothing. Used by the launch path to skip propagating an empty
   * policy across process boundaries: an entirely permissive policy is the bootstrap's own default,
   * so no argv flag needs to travel.
   */
  public boolean isPermissive() {
    return allowedPackages.isEmpty()
        && deniedClasses.isEmpty()
        && deniedPackages.isEmpty()
        && !denyReflection
        && !denyNativeAccess
        && !denyDynamicClassDefinition;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Mutable builder for {@link SandboxPolicy}. All {@code with*} methods replace prior values; the
   * collection setters accept varargs to avoid forcing callers to build a set explicitly.
   */
  public static final class Builder {
    private Set<String> allowedPackages = Set.of();
    private Set<String> deniedClasses = Set.of();
    private Set<String> deniedPackages = Set.of();
    private boolean denyReflection;
    private boolean denyNativeAccess;
    private boolean denyDynamicClassDefinition;
    private ViolationAction onViolation = ViolationAction.THROW;

    private Builder() {}

    /** Opt into allow-list mode. See {@link SandboxPolicy#allowedPackages()}. */
    public Builder withAllowedPackages(String... packages) {
      this.allowedPackages = Set.of(packages);
      return this;
    }

    /** Opt into allow-list mode. See {@link SandboxPolicy#allowedPackages()}. */
    public Builder withAllowedPackages(Set<String> packages) {
      this.allowedPackages = Set.copyOf(packages);
      return this;
    }

    public Builder withDeniedClasses(String... classes) {
      this.deniedClasses = Set.of(classes);
      return this;
    }

    public Builder withDeniedClasses(Set<String> classes) {
      this.deniedClasses = Set.copyOf(classes);
      return this;
    }

    public Builder withDeniedPackages(String... packages) {
      this.deniedPackages = Set.of(packages);
      return this;
    }

    public Builder withDeniedPackages(Set<String> packages) {
      this.deniedPackages = Set.copyOf(packages);
      return this;
    }

    /** Deny the entire reflection family. See {@link SandboxPolicy#denyReflection()}. */
    public Builder withDenyReflection(boolean denyReflection) {
      this.denyReflection = denyReflection;
      return this;
    }

    /** Deny Panama FFI and {@code System.load*}. See {@link SandboxPolicy#denyNativeAccess()}. */
    public Builder withDenyNativeAccess(boolean denyNativeAccess) {
      this.denyNativeAccess = denyNativeAccess;
      return this;
    }

    /**
     * Deny runtime class definition primitives. See {@link
     * SandboxPolicy#denyDynamicClassDefinition()}.
     */
    public Builder withDenyDynamicClassDefinition(boolean denyDynamicClassDefinition) {
      this.denyDynamicClassDefinition = denyDynamicClassDefinition;
      return this;
    }

    public Builder withOnViolation(ViolationAction onViolation) {
      this.onViolation = onViolation;
      return this;
    }

    public SandboxPolicy build() {
      return new SandboxPolicy(
          allowedPackages,
          deniedClasses,
          deniedPackages,
          denyReflection,
          denyNativeAccess,
          denyDynamicClassDefinition,
          onViolation);
    }
  }
}
