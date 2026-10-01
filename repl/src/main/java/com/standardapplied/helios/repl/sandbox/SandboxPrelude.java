/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox;

import ai.singlr.core.common.Strings;
import ai.singlr.repl.host.HostFunction;
import ai.singlr.repl.host.HostFunctionRegistry;
import java.util.Set;
import java.util.stream.Collectors;
import jdk.jshell.JShell;

/**
 * Installs a script-friendly preamble into a {@link JShell} instance at sandbox boot. The preamble
 * adds standard imports, free {@code print}/{@code println}/{@code printf} methods, and a curated
 * set of helpers ({@code sum}, {@code mean}, {@code max}, {@code min}, {@code join}, {@code
 * filter}, {@code map}, {@code sorted}, {@code countBy}) so sandbox code can express common
 * operations as one-liners instead of full {@code Stream} chains.
 *
 * <p>On top of the static preamble, {@link #synthesizeCustomWrappers} generates typed JShell static
 * methods for every non-reserved {@link HostFunction} on the registry. A {@code HostFunction} named
 * {@code marketQuote} with a {@code ticker: STRING} parameter becomes callable as {@code
 * marketQuote("AAPL")} from emitted Java code; the synthesized body packs arguments into a {@code
 * Map<String, Object>} keyed by parameter name and dispatches via {@code HostBridge.__call}.
 *
 * <p>Lives at the sandbox layer so every consumer benefits — direct {@code CodeExecutionTool}
 * users, the RLM harness, and any future composition all see the same surface. Running before any
 * user code guarantees the helpers are visible from the first {@code execute_code} call.
 */
public final class SandboxPrelude {

  /**
   * Function names skipped by the synthesizer because hardcoded {@code HostBridge} static methods
   * already provide the typed signature, or because they are framework-internal relays.
   * Synthesizing a wrapper for these would shadow the hand-written method.
   */
  // Shared canonical reserved-name set — see HostFunctionRegistry.RESERVED_NAMES for the rationale.
  static final Set<String> RESERVED_NAMES = ai.singlr.repl.host.HostFunctionRegistry.RESERVED_NAMES;

  private SandboxPrelude() {}

  /**
   * The static preamble snippet — standard imports + free {@code print}/{@code println} + numeric
   * and collection helpers. Public so users introspecting the sandbox surface (e.g. for
   * documentation) can see exactly what's available; the LLM's system prompt should reference the
   * names but not the bodies.
   */
  public static final String SNIPPET =
      """
      import java.util.*;
      import java.util.stream.*;
      import java.util.stream.Collectors;
      import java.util.function.*;
      import java.util.regex.*;
      import java.io.*;
      import java.math.*;
      import java.time.*;

      void print(Object x) { System.out.print(x); }
      void println() { System.out.println(); }
      void println(Object x) { System.out.println(x); }
      void printf(String fmt, Object... args) { System.out.printf(fmt, args); }

      static double sum(Collection<? extends Number> xs) {
        return xs.stream().mapToDouble(Number::doubleValue).sum();
      }
      static long sumInts(Collection<? extends Number> xs) {
        return xs.stream().mapToLong(Number::longValue).sum();
      }
      static double mean(Collection<? extends Number> xs) {
        return xs.stream().mapToDouble(Number::doubleValue).average().orElse(Double.NaN);
      }
      static double max(Collection<? extends Number> xs) {
        return xs.stream().mapToDouble(Number::doubleValue).max().orElse(Double.NaN);
      }
      static double min(Collection<? extends Number> xs) {
        return xs.stream().mapToDouble(Number::doubleValue).min().orElse(Double.NaN);
      }

      static String join(String sep, Collection<?> xs) {
        return xs.stream().map(String::valueOf).collect(Collectors.joining(sep));
      }
      static <T> List<T> filter(Collection<T> xs, Predicate<? super T> p) {
        return xs.stream().filter(p).collect(Collectors.toList());
      }
      static <T, R> List<R> map(Collection<T> xs, Function<? super T, ? extends R> f) {
        return xs.stream().map(f).collect(Collectors.toList());
      }
      static <T extends Comparable<T>> List<T> sorted(Collection<T> xs) {
        return xs.stream().sorted().collect(Collectors.toList());
      }
      static <T, K> Map<K, Long> countBy(Collection<T> xs, Function<? super T, ? extends K> key) {
        return xs.stream().collect(Collectors.groupingBy(key, Collectors.counting()));
      }
      """;

  /**
   * Install the static preamble into a JShell instance. Splits {@link #SNIPPET} into completion
   * units via {@link jdk.jshell.SourceCodeAnalysis} and evaluates each one in order. Stops (without
   * throwing) on the first incomplete unit, which would only happen if the snippet had a typo —
   * covered by unit tests.
   *
   * @param jshell the JShell instance to install into
   */
  public static void install(JShell jshell) {
    installSource(jshell, SNIPPET);
  }

  /**
   * Synthesize JShell static method wrappers for every non-reserved host function on the registry.
   * Returns an empty string when nothing needs synthesizing (registry empty or only built-ins).
   *
   * <p>Per function:
   *
   * <ul>
   *   <li>Declared parameters become typed JShell method parameters in declaration order.
   *   <li>Parameter values are packed into a {@code Map<String, Object>} keyed by parameter name.
   *   <li>The body dispatches to {@link HostBridge#__call} which forwards over JSON-RPC.
   * </ul>
   *
   * <p>Functions with empty parameter lists synthesize as zero-arg wrappers ({@code static Object
   * foo()}) — handler still receives an empty map. That's almost never what you want; declare
   * parameters explicitly.
   *
   * @param registry the host function registry the parent has built up
   * @return the synthesized JShell snippet, or an empty string if nothing was synthesized
   */
  public static String synthesizeCustomWrappers(HostFunctionRegistry registry) {
    if (registry == null) {
      return "";
    }
    var sb = new StringBuilder();
    for (var fn : registry.all()) {
      if (RESERVED_NAMES.contains(fn.name())) {
        continue;
      }
      sb.append(synthesizeOne(fn));
      sb.append('\n');
    }
    return sb.toString();
  }

  static String synthesizeOne(HostFunction fn) {
    var sb = new StringBuilder();
    var sig =
        fn.parameters().stream()
            .map(p -> p.javaType() + " " + p.name())
            .collect(Collectors.joining(", "));
    sb.append("static Object ").append(fn.name()).append("(").append(sig).append(") {\n");
    if (fn.parameters().isEmpty()) {
      sb.append("  return ai.singlr.repl.sandbox.HostBridge.__call(\"")
          .append(fn.name())
          .append("\", java.util.Map.of());\n");
    } else {
      sb.append("  var __args = new java.util.LinkedHashMap<String, Object>();\n");
      for (var p : fn.parameters()) {
        sb.append("  __args.put(\"")
            .append(p.name())
            .append("\", ")
            .append(p.name())
            .append(");\n");
      }
      sb.append("  return ai.singlr.repl.sandbox.HostBridge.__call(\"")
          .append(fn.name())
          .append("\", __args);\n");
    }
    sb.append("}\n");
    return sb.toString();
  }

  /**
   * Install synthesized custom wrappers from a registry into a JShell instance. Used by the sandbox
   * subprocess after receiving the registry-derived snippet from the parent.
   *
   * @param jshell the JShell instance
   * @param snippet the snippet returned by {@link #synthesizeCustomWrappers}
   */
  public static void installCustomWrappers(JShell jshell, String snippet) {
    if (Strings.isBlank(snippet)) {
      return;
    }
    installSource(jshell, snippet);
  }

  private static void installSource(JShell jshell, String source) {
    var sca = jshell.sourceCodeAnalysis();
    var remaining = source;
    while (!remaining.isBlank()) {
      var info = sca.analyzeCompletion(remaining);
      if (!info.completeness().isComplete()) {
        return;
      }
      jshell.eval(info.source());
      remaining = info.remaining();
    }
  }

  /**
   * Model-facing summary of the static preamble (helpers, imports, conveniences). Listed in the RLM
   * system prompt so the LLM knows the surface without reading the source.
   */
  public static String modelFacingSummary() {
    return """
        Pre-installed in every execute_code call (no imports needed):
          - Standard imports: java.util.*, java.util.stream.*, java.util.function.*, \
        java.util.regex.*, java.io.*, java.math.*, java.time.*, Collectors
          - print(x), println(), println(x), printf(fmt, args...)  — no need for System.out
          - Numeric reductions over any Collection<? extends Number>:
              sum(xs) -> double, sumInts(xs) -> long, mean(xs) -> double, max(xs) -> double, \
        min(xs) -> double
          - Collection helpers:
              join(sep, xs) -> String
              filter(xs, predicate) -> List
              map(xs, function) -> List
              sorted(xs) -> List      (xs must be Comparable)
              countBy(xs, keyFn) -> Map<K, Long>
        Use these instead of writing the equivalent .stream().collect(...) chains by hand.\
        """;
  }

  /**
   * Model-facing summary of the synthesized custom-host-function wrappers. Returns an empty string
   * when the registry has no non-reserved functions. Listed in the RLM system prompt right after
   * {@link #modelFacingSummary()} so the LLM sees the full sandbox surface.
   *
   * @param registry the host function registry whose wrappers will be synthesized
   * @return one line per synthesized wrapper, or an empty string if there are none
   */
  public static String customWrapperSummary(HostFunctionRegistry registry) {
    if (registry == null) {
      return "";
    }
    var lines = new StringBuilder();
    for (var fn : registry.all()) {
      if (RESERVED_NAMES.contains(fn.name())) {
        continue;
      }
      lines.append("  - ").append(formatSignature(fn)).append('\n');
      if (!fn.description().isBlank()) {
        lines.append("      ").append(fn.description()).append('\n');
      }
      for (var p : fn.parameters()) {
        lines.append("      ");
        lines.append(p.required() ? "" : "[optional] ");
        lines
            .append(p.name())
            .append(" (")
            .append(p.type().jsonType())
            .append(") — ")
            .append(p.description())
            .append('\n');
      }
    }
    if (lines.isEmpty()) {
      return "";
    }
    return "Custom host functions registered for this run:\n" + lines.toString().stripTrailing();
  }

  /**
   * Format a {@link HostFunction} as a Java method-style signature ({@code name(Type1 p1, Type2
   * p2)}). Used by both the synthesizer and the system-prompt builder so the model sees the same
   * signature it'll actually call.
   */
  public static String formatSignature(HostFunction fn) {
    var sig =
        fn.parameters().stream()
            .map(p -> p.javaType() + " " + p.name())
            .collect(Collectors.joining(", "));
    return fn.name() + "(" + sig + ")";
  }
}
