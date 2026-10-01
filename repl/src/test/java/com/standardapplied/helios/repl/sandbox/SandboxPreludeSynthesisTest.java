/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.tool.ParameterType;
import ai.singlr.repl.host.HostFunction;
import ai.singlr.repl.host.HostFunctionRegistry;
import ai.singlr.repl.host.HostParameter;
import java.util.List;
import jdk.jshell.JShell;
import jdk.jshell.Snippet;
import org.junit.jupiter.api.Test;

class SandboxPreludeSynthesisTest {

  @Test
  void synthesizeReturnsEmptyWhenRegistryIsEmpty() {
    var registry = new HostFunctionRegistry();
    assertEquals("", SandboxPrelude.synthesizeCustomWrappers(registry));
  }

  @Test
  void synthesizeReturnsEmptyWhenRegistryHasOnlyReservedNames() {
    var registry = new HostFunctionRegistry();
    registry.register(new HostFunction("predict", "x", p -> ""));
    registry.register(new HostFunction("submit", "x", p -> ""));
    registry.register(new HostFunction("__getInput", "x", p -> ""));
    assertEquals("", SandboxPrelude.synthesizeCustomWrappers(registry));
  }

  @Test
  void synthesizeNullRegistryReturnsEmpty() {
    assertEquals("", SandboxPrelude.synthesizeCustomWrappers(null));
  }

  @Test
  void synthesizeOneParamFunction() {
    var fn =
        new HostFunction(
            "marketQuote",
            "Get a stock quote",
            List.of(HostParameter.required("ticker", ParameterType.STRING, "Ticker symbol")),
            p -> "stub");
    var snippet = SandboxPrelude.synthesizeOne(fn);
    // Exact signature shape — the model will see this in the system prompt.
    assertTrue(
        snippet.contains("static Object marketQuote(java.lang.String ticker)"),
        "expected typed signature, got:\n" + snippet);
    // The body packs args by parameter name and dispatches to HostBridge.__call.
    assertTrue(snippet.contains("__args.put(\"ticker\", ticker)"));
    assertTrue(snippet.contains("ai.singlr.repl.sandbox.HostBridge.__call(\"marketQuote\""));
    assertFalse(
        snippet.contains("Map.of"),
        "non-empty params must use a LinkedHashMap so optional/null values don't trip Map.of's"
            + " null-prohibition");
  }

  @Test
  void synthesizeMultiParamFunctionPreservesDeclarationOrder() {
    var fn =
        new HostFunction(
            "insiderTransactions",
            "Recent insider trades",
            List.of(
                HostParameter.required("ticker", ParameterType.STRING, "x"),
                HostParameter.optional("since", ParameterType.STRING, "x"),
                HostParameter.optional("limit", ParameterType.INTEGER, "x")),
            p -> List.of());
    var snippet = SandboxPrelude.synthesizeOne(fn);
    assertTrue(
        snippet.contains(
            "static Object insiderTransactions(java.lang.String ticker, java.lang.String since,"
                + " java.lang.Long limit)"),
        "params must keep declaration order in synthesized signature, got:\n" + snippet);
    var ticker = snippet.indexOf("__args.put(\"ticker\"");
    var since = snippet.indexOf("__args.put(\"since\"");
    var limit = snippet.indexOf("__args.put(\"limit\"");
    assertTrue(ticker > 0 && since > ticker && limit > since, "puts must follow declaration order");
  }

  @Test
  void synthesizeZeroParamFunctionIsZeroArgWrapper() {
    var fn = new HostFunction("listSymbols", "All known tickers", p -> List.of("AAPL"));
    var snippet = SandboxPrelude.synthesizeOne(fn);
    assertTrue(
        snippet.contains("static Object listSymbols()"),
        "zero-param functions become zero-arg wrappers, got:\n" + snippet);
    assertTrue(snippet.contains("java.util.Map.of()"));
    assertFalse(snippet.contains("__args"));
  }

  @Test
  void synthesizeAllParameterTypes() {
    var fn =
        new HostFunction(
            "kitchenSink",
            "Exercises every type",
            List.of(
                HostParameter.required("s", ParameterType.STRING, "x"),
                HostParameter.required("n", ParameterType.INTEGER, "x"),
                HostParameter.required("d", ParameterType.NUMBER, "x"),
                HostParameter.required("b", ParameterType.BOOLEAN, "x"),
                HostParameter.required("xs", ParameterType.ARRAY, "x"),
                HostParameter.required("m", ParameterType.OBJECT, "x")),
            p -> "ok");
    var snippet = SandboxPrelude.synthesizeOne(fn);
    assertTrue(snippet.contains("java.lang.String s"));
    assertTrue(snippet.contains("java.lang.Long n"));
    assertTrue(snippet.contains("java.lang.Double d"));
    assertTrue(snippet.contains("java.lang.Boolean b"));
    assertTrue(snippet.contains("java.util.List<java.lang.Object> xs"));
    assertTrue(snippet.contains("java.util.Map<java.lang.String, java.lang.Object> m"));
  }

  @Test
  void synthesizeRegistrySkipsReservedNames() {
    var registry = new HostFunctionRegistry();
    registry.register(new HostFunction("predict", "x", p -> ""));
    registry.register(
        new HostFunction(
            "marketQuote",
            "x",
            List.of(HostParameter.required("ticker", ParameterType.STRING, "x")),
            p -> ""));
    registry.register(new HostFunction("__getInput", "x", p -> ""));
    var snippet = SandboxPrelude.synthesizeCustomWrappers(registry);
    assertFalse(snippet.contains("static Object predict("), "predict is reserved");
    assertFalse(snippet.contains("static Object __getInput("), "__getInput is reserved");
    assertTrue(snippet.contains("static Object marketQuote("));
  }

  @Test
  void synthesizedSnippetCompilesInJShell() {
    // The strongest signal: feed the synthesized snippet to a real JShell instance and verify
    // every event is VALID. Catches typos and Java-syntax bugs in the codegen.
    try (var jshell = JShell.builder().executionEngine("local").build()) {
      // The wrapper body references HostBridge — make it resolvable for compilation.
      var bridgeJar =
          ai.singlr.repl.sandbox.HostBridge.class
              .getProtectionDomain()
              .getCodeSource()
              .getLocation();
      jshell.addToClasspath(java.nio.file.Paths.get(bridgeJar.toURI()).toString());

      var registry = new HostFunctionRegistry();
      registry.register(
          new HostFunction(
              "marketQuote",
              "Get a quote",
              List.of(HostParameter.required("ticker", ParameterType.STRING, "x")),
              p -> ""));
      registry.register(
          new HostFunction(
              "fredIndicator",
              "Get an econ indicator",
              List.of(
                  HostParameter.required("seriesId", ParameterType.STRING, "x"),
                  HostParameter.optional("limit", ParameterType.INTEGER, "x")),
              p -> ""));
      registry.register(new HostFunction("listSymbols", "Zero-arg", p -> List.of()));

      var snippet = SandboxPrelude.synthesizeCustomWrappers(registry);
      SandboxPrelude.installCustomWrappers(jshell, snippet);

      var methods = jshell.methods().toList();
      assertTrue(methods.stream().anyMatch(m -> "marketQuote".equals(m.name())));
      assertTrue(methods.stream().anyMatch(m -> "fredIndicator".equals(m.name())));
      assertTrue(methods.stream().anyMatch(m -> "listSymbols".equals(m.name())));
      // Every method must be VALID — synthesized code can't have Java errors.
      for (var m : methods) {
        assertEquals(
            Snippet.Status.VALID, jshell.status(m), "method " + m.name() + " must compile");
      }
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  @Test
  void installCustomWrappersIgnoresEmptyOrBlank() {
    try (var jshell = JShell.builder().executionEngine("local").build()) {
      // Doesn't throw, doesn't install anything.
      SandboxPrelude.installCustomWrappers(jshell, "");
      SandboxPrelude.installCustomWrappers(jshell, null);
      SandboxPrelude.installCustomWrappers(jshell, "   \n  ");
      assertEquals(0, jshell.methods().count());
    }
  }

  @Test
  void formatSignatureMatchesSynthesizedShape() {
    var fn =
        new HostFunction(
            "marketQuote",
            "x",
            List.of(HostParameter.required("ticker", ParameterType.STRING, "x")),
            p -> "");
    assertEquals("marketQuote(java.lang.String ticker)", SandboxPrelude.formatSignature(fn));
  }

  @Test
  void formatSignatureForZeroArg() {
    var fn = new HostFunction("listSymbols", "x", p -> "");
    assertEquals("listSymbols()", SandboxPrelude.formatSignature(fn));
  }

  @Test
  void customWrapperSummaryReturnsEmptyWhenNothingToShow() {
    var registry = new HostFunctionRegistry();
    assertEquals("", SandboxPrelude.customWrapperSummary(registry));
    assertEquals("", SandboxPrelude.customWrapperSummary(null));

    var only = new HostFunctionRegistry();
    only.register(new HostFunction("predict", "x", p -> ""));
    assertEquals("", SandboxPrelude.customWrapperSummary(only));
  }

  @Test
  void customWrapperSummaryListsSignatureAndParams() {
    var registry = new HostFunctionRegistry();
    registry.register(
        new HostFunction(
            "marketQuote",
            "Get a current quote",
            List.of(HostParameter.required("ticker", ParameterType.STRING, "Ticker symbol")),
            p -> ""));
    var summary = SandboxPrelude.customWrapperSummary(registry);
    assertTrue(summary.contains("Custom host functions registered for this run:"));
    assertTrue(summary.contains("marketQuote(java.lang.String ticker)"));
    assertTrue(summary.contains("Get a current quote"));
    assertTrue(summary.contains("ticker (string) — Ticker symbol"));
  }

  @Test
  void reservedNamesSetIncludesAllHostBridgeMethods() {
    // Belt-and-suspenders test: any HostBridge.* static method (predict, submit, fetch, query,
    // getInput, __call) MUST be in RESERVED_NAMES so the synthesizer never shadows it. If
    // HostBridge gains a new public static method we want this test to fail until RESERVED_NAMES
    // is updated.
    assertTrue(SandboxPrelude.RESERVED_NAMES.contains("predict"));
    assertTrue(SandboxPrelude.RESERVED_NAMES.contains("submit"));
    assertTrue(SandboxPrelude.RESERVED_NAMES.contains("fetch"));
    assertTrue(SandboxPrelude.RESERVED_NAMES.contains("query"));
    assertTrue(SandboxPrelude.RESERVED_NAMES.contains("getInput"));
    assertTrue(SandboxPrelude.RESERVED_NAMES.contains("__getInput"));
    assertTrue(SandboxPrelude.RESERVED_NAMES.contains("__call"));
  }
}
