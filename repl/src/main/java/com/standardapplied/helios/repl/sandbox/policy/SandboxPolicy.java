/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.repl.sandbox.policy;

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
 * kernel. The sandbox subprocess runs as the same user and in the same namespaces as the host
 * process, so a private working directory is not filesystem or network isolation. Deployers running
 * untrusted snippet payloads must arrange external isolation around the process that owns the
 * sandbox; the README's "Least-privilege deployment" section documents the supported recipe.
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
 * @param denyFileSystemAccess if {@code true}, denies every snippet-callable path to the host
 *     filesystem. Closes the read/write IO surface (the {@code File*Stream}, {@code FileReader},
 *     {@code FileWriter}, {@code RandomAccessFile} family plus the {@code FileChannel} / {@code
 *     AsynchronousFileChannel} channels and the entire {@code java.nio.file} package — so {@code
 *     Files.readAllBytes}, {@code Files.list}, {@code Files.walk}, {@code Paths.get} and friends
 *     are denied), and closes the metadata-leak surface on {@code java.io.File} itself ({@code
 *     list} / {@code listFiles} / {@code exists} / {@code length} / {@code isDirectory} / {@code
 *     canRead} and the other filesystem-touching members). Construction of {@code new File(path)}
 *     and the purely-string methods ({@code getName}, {@code getPath}, {@code getAbsolutePath},
 *     etc.) stay callable because they don't reach the filesystem.
 *     <p>This is the right flag to set whenever the snippet's filesystem access should funnel
 *     through host-owned tools (e.g. the session's {@code Read} / {@code Grep} / {@code Glob} tools
 *     rooted at a curated {@code WorkspaceRoot}) rather than raw {@code Files.readAllBytes} — set
 *     this flag, then expose the curated path-jail surface as host tools. Combine with {@link
 *     com.standardapplied.helios.repl.sandbox.JvmSandboxConfig#workingDirectory()} for
 *     defense-in-depth on relative-path resolution.
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
    boolean denyFileSystemAccess,
    ViolationAction onViolation) {

  /**
   * Validates the components and stores immutable copies of the three sets.
   *
   * @throws IllegalArgumentException if any set or {@code onViolation} is {@code null}, or if any
   *     set holds a {@code null} or blank entry
   */
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
   *
   * @return a policy with every set empty, every deny flag off and {@link ViolationAction#THROW}
   */
  public static SandboxPolicy permissive() {
    return new SandboxPolicy(
        Set.of(), Set.of(), Set.of(), false, false, false, false, ViolationAction.THROW);
  }

  /**
   * Curated preset: "no egress". Lets the snippet read inputs, do compute, manipulate collections,
   * format numbers and times, write to stdout / stderr — but blocks every path out of the sandbox:
   * no process spawn, no network, no file IO (including the {@code java.io.File} metadata-leak
   * surface and the {@code java.nio.file} package), no reflection escape, no native code, no
   * dynamic class definition.
   *
   * <p>Composition:
   *
   * <ul>
   *   <li>{@code allowedPackages} = {@code java.lang}, {@code java.util}, {@code java.util.stream},
   *       {@code java.util.function}, {@code java.util.regex}, {@code java.util.concurrent.atomic},
   *       {@code java.math}, {@code java.time}, {@code java.time.format}, {@code
   *       java.time.temporal}, {@code java.text}, {@code java.io} (for {@code System.out.println},
   *       formatted output, in-memory streams) — the safe compute and stdio surface
   *   <li>{@code deniedClasses} = the non-filesystem dangerous classes inside the allowed packages:
   *       {@code java.lang.ProcessBuilder}, {@code java.lang.Runtime}, {@code java.lang.Thread},
   *       {@code java.lang.ThreadGroup}, plus the serialization classes in {@code java.io} ({@code
   *       ObjectInputStream}, {@code ObjectOutputStream}). File IO is no longer enumerated here —
   *       {@code denyFileSystemAccess} below covers every {@code FileReader} / {@code FileWriter} /
   *       {@code File*Stream} / {@code RandomAccessFile} opener plus the metadata-leak path on
   *       {@code java.io.File} that previously survived this preset
   *   <li>{@code denyReflection}, {@code denyNativeAccess}, {@code denyDynamicClassDefinition},
   *       {@code denyFileSystemAccess} all enabled — every categorical escape hatch closed
   * </ul>
   *
   * <p>Deployers who need a single API call to lock the agent into "data crunching with no egress"
   * reach for this preset and customise from there. Returns a fresh {@link SandboxPolicy}; pass
   * directly to {@link
   * com.standardapplied.helios.repl.sandbox.JvmSandboxConfig.Builder#withSandboxPolicy(SandboxPolicy)}
   * or compose by round-tripping through a Builder.
   *
   * @return a new no-egress policy as composed above, reporting violations with {@link
   *     ViolationAction#THROW}
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
            "java.io.ObjectInputStream",
            "java.io.ObjectOutputStream"),
        Set.of(),
        true,
        true,
        true,
        true,
        ViolationAction.THROW);
  }

  /**
   * Whether this policy enforces nothing. Used by the launch path to skip propagating an empty
   * policy across process boundaries: an entirely permissive policy is the bootstrap's own default,
   * so no argv flag needs to travel.
   *
   * @return {@code true} when all three sets are empty and every deny flag is off; {@code
   *     onViolation} is not considered
   */
  public boolean enforcesNothing() {
    return allowedPackages.isEmpty()
        && deniedClasses.isEmpty()
        && deniedPackages.isEmpty()
        && !denyReflection
        && !denyNativeAccess
        && !denyDynamicClassDefinition
        && !denyFileSystemAccess;
  }

  /**
   * Starts a builder whose defaults equal {@link #permissive()}: empty sets, every deny flag off,
   * {@link ViolationAction#THROW}.
   *
   * @return a new builder
   */
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
    private boolean denyFileSystemAccess;
    private ViolationAction onViolation = ViolationAction.THROW;

    private Builder() {}

    /**
     * Opts into allow-list mode, replacing any earlier allow-list; no arguments turns it off. See
     * {@link SandboxPolicy#allowedPackages()}.
     *
     * @param packages JDK package prefixes a snippet may call into, such as {@code "java.util"};
     *     neither the array nor an element may be {@code null}, and no prefix may repeat
     * @return this builder
     * @throws NullPointerException if {@code packages} or an element is {@code null}
     * @throws IllegalArgumentException if a prefix repeats
     */
    public Builder withAllowedPackages(String... packages) {
      this.allowedPackages = Set.of(packages);
      return this;
    }

    /**
     * Opts into allow-list mode with a copy of {@code packages}, replacing any earlier allow-list;
     * an empty set turns it off. See {@link SandboxPolicy#allowedPackages()}.
     *
     * @param packages JDK package prefixes a snippet may call into; neither the set nor an element
     *     may be {@code null}
     * @return this builder
     * @throws NullPointerException if {@code packages} or an element is {@code null}
     */
    public Builder withAllowedPackages(Set<String> packages) {
      this.allowedPackages = Set.copyOf(packages);
      return this;
    }

    /**
     * Sets the classes a snippet must not reference, replacing any earlier set; none by default.
     * See {@link SandboxPolicy#deniedClasses()}.
     *
     * @param classes fully-qualified class names, such as {@code "java.lang.ProcessBuilder"};
     *     neither the array nor an element may be {@code null}, and no name may repeat
     * @return this builder
     * @throws NullPointerException if {@code classes} or an element is {@code null}
     * @throws IllegalArgumentException if a name repeats
     */
    public Builder withDeniedClasses(String... classes) {
      this.deniedClasses = Set.of(classes);
      return this;
    }

    /**
     * Sets the classes a snippet must not reference to a copy of {@code classes}, replacing any
     * earlier set; none by default. See {@link SandboxPolicy#deniedClasses()}.
     *
     * @param classes fully-qualified class names; neither the set nor an element may be {@code
     *     null}
     * @return this builder
     * @throws NullPointerException if {@code classes} or an element is {@code null}
     */
    public Builder withDeniedClasses(Set<String> classes) {
      this.deniedClasses = Set.copyOf(classes);
      return this;
    }

    /**
     * Sets the package prefixes whose classes and sub-packages a snippet must not reference,
     * replacing any earlier set; none by default. See {@link SandboxPolicy#deniedPackages()}.
     *
     * @param packages package prefixes, such as {@code "java.net.http"}; neither the array nor an
     *     element may be {@code null}, and no prefix may repeat
     * @return this builder
     * @throws NullPointerException if {@code packages} or an element is {@code null}
     * @throws IllegalArgumentException if a prefix repeats
     */
    public Builder withDeniedPackages(String... packages) {
      this.deniedPackages = Set.of(packages);
      return this;
    }

    /**
     * Sets the denied package prefixes to a copy of {@code packages}, replacing any earlier set;
     * none by default. See {@link SandboxPolicy#deniedPackages()}.
     *
     * @param packages package prefixes; neither the set nor an element may be {@code null}
     * @return this builder
     * @throws NullPointerException if {@code packages} or an element is {@code null}
     */
    public Builder withDeniedPackages(Set<String> packages) {
      this.deniedPackages = Set.copyOf(packages);
      return this;
    }

    /**
     * Denies the entire reflection family when {@code true}; off by default. See {@link
     * SandboxPolicy#denyReflection()}.
     *
     * @param denyReflection whether reflective and method-handle entry points are denied
     * @return this builder
     */
    public Builder withDenyReflection(boolean denyReflection) {
      this.denyReflection = denyReflection;
      return this;
    }

    /**
     * Denies Panama FFI and {@code System.load*} when {@code true}; off by default. See {@link
     * SandboxPolicy#denyNativeAccess()}.
     *
     * @param denyNativeAccess whether native-code entry points are denied
     * @return this builder
     */
    public Builder withDenyNativeAccess(boolean denyNativeAccess) {
      this.denyNativeAccess = denyNativeAccess;
      return this;
    }

    /**
     * Denies runtime class definition primitives when {@code true}; off by default. See {@link
     * SandboxPolicy#denyDynamicClassDefinition()}.
     *
     * @param denyDynamicClassDefinition whether {@code defineClass} and {@code defineHiddenClass}
     *     are denied
     * @return this builder
     */
    public Builder withDenyDynamicClassDefinition(boolean denyDynamicClassDefinition) {
      this.denyDynamicClassDefinition = denyDynamicClassDefinition;
      return this;
    }

    /**
     * Deny every snippet-callable path to the host filesystem — {@code File*Stream}, {@code
     * FileReader}, {@code FileWriter}, {@code RandomAccessFile}, {@code FileChannel} / {@code
     * AsynchronousFileChannel}, the entire {@code java.nio.file} package, and the
     * filesystem-touching members of {@code java.io.File}. See {@link
     * SandboxPolicy#denyFileSystemAccess()}. Off by default.
     *
     * @param denyFileSystemAccess whether every filesystem-touching API is denied
     * @return this builder
     */
    public Builder withDenyFileSystemAccess(boolean denyFileSystemAccess) {
      this.denyFileSystemAccess = denyFileSystemAccess;
      return this;
    }

    /**
     * Sets how the verifier reports a violation; {@link ViolationAction#THROW} by default.
     *
     * @param onViolation the reporting mode; {@code null} is rejected by {@link #build()}
     * @return this builder
     */
    public Builder withOnViolation(ViolationAction onViolation) {
      this.onViolation = onViolation;
      return this;
    }

    /**
     * Builds the policy from the current settings.
     *
     * @return a new immutable policy
     * @throws IllegalArgumentException if {@code onViolation} is {@code null} or any set holds a
     *     blank entry
     */
    public SandboxPolicy build() {
      return new SandboxPolicy(
          allowedPackages,
          deniedClasses,
          deniedPackages,
          denyReflection,
          denyNativeAccess,
          denyDynamicClassDefinition,
          denyFileSystemAccess,
          onViolation);
    }
  }
}
