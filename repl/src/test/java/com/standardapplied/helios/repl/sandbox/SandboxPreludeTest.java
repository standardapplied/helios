/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import jdk.jshell.JShell;
import jdk.jshell.Snippet;
import org.junit.jupiter.api.Test;

class SandboxPreludeTest {

  @Test
  void snippetIsNonEmptyAndContainsExpectedHelpers() {
    var src = SandboxPrelude.SNIPPET;
    assertNotNull(src);
    assertFalse(src.isBlank());

    // Imports
    assertTrue(src.contains("import java.util.stream.*"));
    assertTrue(src.contains("import java.util.function.*"));
    assertTrue(src.contains("import java.util.stream.Collectors"));

    // PRINTING-equivalent
    assertTrue(src.contains("void println()"));
    assertTrue(src.contains("void println(Object x)"));
    assertTrue(src.contains("void printf(String fmt, Object... args)"));

    // Numeric reductions
    assertTrue(src.contains("static double sum("));
    assertTrue(src.contains("static long sumInts("));
    assertTrue(src.contains("static double mean("));
    assertTrue(src.contains("static double max("));
    assertTrue(src.contains("static double min("));

    // Collection helpers
    assertTrue(src.contains("static String join("));
    assertTrue(src.contains("static <T> List<T> filter("));
    assertTrue(src.contains("static <T, R> List<R> map("));
    assertTrue(src.contains("static <T extends Comparable<T>> List<T> sorted("));
    assertTrue(src.contains("static <T, K> Map<K, Long> countBy("));
  }

  @Test
  void modelFacingSummaryListsTheHelpers() {
    var summary = SandboxPrelude.modelFacingSummary();
    assertTrue(summary.contains("println"));
    assertTrue(summary.contains("sum(xs)"));
    assertTrue(summary.contains("mean(xs)"));
    assertTrue(summary.contains("max(xs)"));
    assertTrue(summary.contains("min(xs)"));
    assertTrue(summary.contains("join("));
    assertTrue(summary.contains("filter("));
    assertTrue(summary.contains("map("));
    assertTrue(summary.contains("sorted("));
    assertTrue(summary.contains("countBy("));
  }

  @Test
  void installEvaluatesEverySnippetWithoutErrors() {
    try (var jshell = JShell.builder().executionEngine("local").build()) {
      SandboxPrelude.install(jshell);
      var failed = jshell.snippets().filter(s -> jshell.status(s) != Snippet.Status.VALID).toList();
      assertTrue(
          failed.isEmpty(),
          "Every snippet in the prelude must compile and become VALID; failures: " + failed);
    }
  }

  @Test
  void installedPrintlnIsCallableWithoutSystemOut() {
    try (var jshell = JShell.builder().executionEngine("local").build()) {
      SandboxPrelude.install(jshell);
      var events = jshell.eval("println(\"hello\");");
      assertTrue(
          events.stream().allMatch(e -> e.exception() == null),
          "println(...) must compile and run after prelude install: " + events);
    }
  }

  @Test
  void installedSumOverListReturnsExpectedValue() {
    try (var jshell = JShell.builder().executionEngine("local").build()) {
      SandboxPrelude.install(jshell);
      jshell.eval("var xs = java.util.List.of(1, 2, 3, 4, 5);");
      var events = jshell.eval("sum(xs);");
      assertEquals(1, events.size());
      assertEquals("15.0", events.get(0).value(), "sum(1+2+3+4+5) = 15.0");
    }
  }

  @Test
  void installedMeanMaxMinReturnExpectedValues() {
    try (var jshell = JShell.builder().executionEngine("local").build()) {
      SandboxPrelude.install(jshell);
      jshell.eval("var xs = java.util.List.of(2, 4, 6, 8);");

      assertEquals("5.0", jshell.eval("mean(xs);").get(0).value());
      assertEquals("8.0", jshell.eval("max(xs);").get(0).value());
      assertEquals("2.0", jshell.eval("min(xs);").get(0).value());
    }
  }

  @Test
  void installedSumIntsAvoidsBoxingForLongResults() {
    try (var jshell = JShell.builder().executionEngine("local").build()) {
      SandboxPrelude.install(jshell);
      jshell.eval("var xs = java.util.List.of(10, 20, 30);");
      var events = jshell.eval("sumInts(xs);");
      assertEquals("60", events.get(0).value());
    }
  }

  @Test
  void installedJoinProducesExpectedString() {
    try (var jshell = JShell.builder().executionEngine("local").build()) {
      SandboxPrelude.install(jshell);
      jshell.eval("var xs = java.util.List.of(\"a\", \"b\", \"c\");");
      var events = jshell.eval("join(\", \", xs);");
      assertEquals("\"a, b, c\"", events.get(0).value());
    }
  }

  @Test
  void installedFilterReturnsTypedList() {
    try (var jshell = JShell.builder().executionEngine("local").build()) {
      SandboxPrelude.install(jshell);
      jshell.eval("var xs = java.util.List.of(1, 2, 3, 4, 5);");
      var events = jshell.eval("filter(xs, x -> x > 2);");
      var value = events.get(0).value();
      assertEquals("[3, 4, 5]", value);
    }
  }

  @Test
  void installedMapTransformsAndReturnsTypedList() {
    try (var jshell = JShell.builder().executionEngine("local").build()) {
      SandboxPrelude.install(jshell);
      jshell.eval("var xs = java.util.List.of(1, 2, 3);");
      var events = jshell.eval("map(xs, x -> x * 10);");
      assertEquals("[10, 20, 30]", events.get(0).value());
    }
  }

  @Test
  void installedSortedReturnsAscending() {
    try (var jshell = JShell.builder().executionEngine("local").build()) {
      SandboxPrelude.install(jshell);
      jshell.eval("var xs = java.util.List.of(3, 1, 4, 1, 5, 9, 2, 6);");
      var events = jshell.eval("sorted(xs);");
      assertEquals("[1, 1, 2, 3, 4, 5, 6, 9]", events.get(0).value());
    }
  }

  @Test
  void installedCountByGroupsAndCounts() {
    try (var jshell = JShell.builder().executionEngine("local").build()) {
      SandboxPrelude.install(jshell);
      jshell.eval("var xs = java.util.List.of(\"aa\", \"bb\", \"ccc\", \"dd\");");
      var events = jshell.eval("countBy(xs, s -> s.length());");
      var value = events.get(0).value();
      // Order in toString may vary; verify the entries.
      assertTrue(value.contains("2=3"), "three entries of length 2");
      assertTrue(value.contains("3=1"), "one entry of length 3");
    }
  }

  @Test
  void preInstalledImportsLetStreamCodeCompileWithoutFqn() {
    try (var jshell = JShell.builder().executionEngine("local").build()) {
      SandboxPrelude.install(jshell);
      // Use Stream/Collectors without fully-qualifying — relies on the prelude imports.
      var events = jshell.eval("Stream.of(1, 2, 3).collect(Collectors.toList());");
      var value = events.get(0).value();
      assertEquals("[1, 2, 3]", value);
    }
  }

  @Test
  void modelHelperReplacesVerboseStreamIdiomForSum() {
    // Concretely demonstrate the win: an LLM-realistic "sum a list of integers" task is now
    // one helper call instead of the verbose Stream chain.
    try (var jshell = JShell.builder().executionEngine("local").build()) {
      SandboxPrelude.install(jshell);
      jshell.eval("var numbers = java.util.List.of(1, 5, 3, 2, 4);");
      var verbose = jshell.eval("numbers.stream().mapToInt(Integer::intValue).sum();");
      var concise = jshell.eval("(int) sum(numbers);");
      assertEquals("15", verbose.get(0).value());
      assertEquals("15", concise.get(0).value());
    }
  }

  @Test
  void preludeIsValidlyParseable() {
    // Defensive: catch typos in the snippet by ensuring SourceCodeAnalysis can split it
    // into snippets without ever stalling on an incomplete unit.
    try (var jshell = JShell.builder().executionEngine("local").build()) {
      var sca = jshell.sourceCodeAnalysis();
      var remaining = SandboxPrelude.SNIPPET;
      var unitsParsed = 0;
      while (!remaining.isBlank()) {
        var info = sca.analyzeCompletion(remaining);
        assertTrue(
            info.completeness().isComplete(),
            "Incomplete snippet at: ..."
                + remaining.substring(0, Math.min(80, remaining.length())));
        unitsParsed++;
        remaining = info.remaining();
      }
      assertTrue(unitsParsed >= List.of("imports", "println", "sum", "mean", "join").size());
    }
  }
}
