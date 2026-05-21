/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * JDK-module restriction the {@link JvmSandbox} subprocess launches under. Maps to the {@code
 * --limit-modules} JVM argument — when enabled, packages outside the resolved module graph are
 * non-observable in the subprocess, so {@code import java.net.http.HttpClient} (and equivalent)
 * fails to compile in JShell with a clean error before the L2 bytecode verifier ever runs.
 *
 * <p>L3 in the layered sandbox defense model. Composes with {@link
 * ai.singlr.repl.sandbox.policy.SandboxPolicy SandboxPolicy} (L2 bytecode rules) — limit-modules
 * eliminates whole categories at compile time, the verifier catches the rest at load time, and OS
 * isolation remains the authoritative perimeter (L5).
 *
 * <p><strong>Bootstrap-transitive-closure limit.</strong> {@code --limit-modules} restricts the
 * observable module universe but cannot strip modules that are transitively required by the
 * bootstrap subprocess's own classes. Today {@code ai.singlr.core} (a transitive dependency of the
 * sandbox bootstrap) requires {@code java.net.http} via {@code HttpClientFactory}, so {@code
 * java.net.http} stays in the resolved module graph under any {@link #minimal()} or {@link
 * #allowingExtras(String...)} configuration. {@link #minimal()} does successfully strip many other
 * modules ({@code java.sql}, {@code java.naming}, {@code java.scripting}, {@code java.desktop},
 * {@code java.security.sasl}, {@code jdk.httpserver}, ...) that the bootstrap doesn't transitively
 * require — those become non-observable at compile time, so snippets that {@code import java.sql.*}
 * fail with a clean diagnostic. Deployers who need to deny modules the bootstrap pulls in (e.g.
 * {@code java.net.http}) should reach for {@link
 * ai.singlr.repl.sandbox.policy.SandboxPolicy#deniedPackages() L2 deniedPackages} instead.
 *
 * <p>Sealed: callers exhaustively pattern-match on the variant. Three factories cover the realistic
 * shapes:
 *
 * <ul>
 *   <li>{@link #unrestricted()} — no {@code --limit-modules}, all JDK modules observable. Current
 *       (pre-L3) behaviour and default.
 *   <li>{@link #minimal()} — required modules only ({@code java.base} + JShell's compile chain +
 *       {@code ai.singlr.repl}). Strips everything else, including network ({@code java.net.http}),
 *       JDBC ({@code java.sql}), JMX, scripting, smartcard, JNDI, Kerberos, …
 *   <li>{@link #allowingExtras(String...)} — required modules plus the named extras. Common
 *       intermediate posture, e.g. {@code allowingExtras("java.net.http")} for a snippet that
 *       legitimately needs HTTP.
 * </ul>
 */
public sealed interface SubprocessModules
    permits SubprocessModules.Unrestricted, SubprocessModules.Restricted {

  /**
   * Modules the bootstrap subprocess always needs to start and run JShell. The transitive
   * dependencies of these modules are pulled in by the module resolver, so this is the minimal
   * <em>root</em> set rather than the full module-graph closure.
   *
   * <p>Includes:
   *
   * <ul>
   *   <li>{@code java.base} — required by every Java program
   *   <li>{@code java.compiler}, {@code jdk.compiler}, {@code jdk.jshell} — JShell's
   *       compile-and-eval chain
   *   <li>{@code ai.singlr.repl} — the bootstrap module (transitively pulls {@code ai.singlr.core},
   *       {@code ai.singlr.session}, {@code java.logging}, {@code java.management}, {@code
   *       tools.jackson.databind})
   * </ul>
   */
  List<String> REQUIRED_ROOTS =
      List.of("java.base", "java.compiler", "jdk.compiler", "jdk.jshell", "ai.singlr.repl");

  /**
   * The default — no restriction. Equivalent to the launch behaviour before L3 landed: the
   * subprocess JVM inherits the full observable module set ({@code java.net.http}, {@code
   * java.sql}, {@code java.management}, etc. all reachable).
   */
  static SubprocessModules unrestricted() {
    return Unrestricted.INSTANCE;
  }

  /**
   * Restrict to {@link #REQUIRED_ROOTS} only. Everything else — network, JDBC, JMX, scripting,
   * smartcard, JNDI, Kerberos, foreign linker, ... — becomes non-observable in the subprocess JVM.
   * Snippets that {@code import java.net.http.HttpClient} fail to compile inside JShell with a
   * clean diagnostic, before the L2 verifier even runs.
   */
  static SubprocessModules minimal() {
    return new Restricted(Set.of());
  }

  /**
   * Restrict to {@link #REQUIRED_ROOTS} plus the named extras. Use for snippets that legitimately
   * need a specific JDK module — e.g. {@code allowingExtras("java.net.http")} for an HTTP-using
   * workload, {@code allowingExtras("java.net.http", "java.sql")} for HTTP+JDBC.
   *
   * @throws IllegalArgumentException if {@code modules} is null, contains null, or contains blank
   *     entries
   */
  static SubprocessModules allowingExtras(String... modules) {
    if (modules == null) {
      throw new IllegalArgumentException("modules must not be null");
    }
    var deduped = new LinkedHashSet<String>();
    for (var m : modules) {
      if (m == null || m.isBlank()) {
        throw new IllegalArgumentException("module name must not be null or blank");
      }
      deduped.add(m);
    }
    return new Restricted(Set.copyOf(deduped));
  }

  /**
   * Build the comma-separated argument value for {@code --limit-modules}, or return empty when this
   * variant is {@link Unrestricted}. The caller appends {@code "--limit-modules"} + the result to
   * the launch command iff the result is non-empty.
   */
  String limitModulesArg();

  /** Singleton variant — no module restriction applied at launch. */
  record Unrestricted() implements SubprocessModules {
    private static final Unrestricted INSTANCE = new Unrestricted();

    @Override
    public String limitModulesArg() {
      return "";
    }
  }

  /**
   * Restricted variant — emits {@code --limit-modules} with {@link #REQUIRED_ROOTS} plus {@code
   * extraModules}. Extras are deduplicated against the required roots at argument-building time
   * (configuring {@code extras = {"java.base"}} is a no-op rather than an error — minor caller
   * convenience).
   */
  record Restricted(Set<String> extraModules) implements SubprocessModules {
    public Restricted {
      if (extraModules == null) {
        throw new IllegalArgumentException("extraModules must not be null (use empty set instead)");
      }
      for (var m : extraModules) {
        if (m == null || m.isBlank()) {
          throw new IllegalArgumentException("module name must not be null or blank");
        }
      }
      extraModules = Set.copyOf(extraModules);
    }

    @Override
    public String limitModulesArg() {
      var ordered = new LinkedHashSet<String>(REQUIRED_ROOTS);
      ordered.addAll(extraModules);
      return String.join(",", ordered);
    }
  }
}
