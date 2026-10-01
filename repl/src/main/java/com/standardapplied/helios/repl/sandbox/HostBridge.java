/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Static bridge for sandbox code to call host functions. Sandbox code evaluated by JShell calls
 * these static methods directly, which delegate to the running {@link JvmSandboxBootstrap}
 * instance.
 *
 * <p>Usage from sandbox code (after {@code import static} pre-eval):
 *
 * <pre>{@code
 * var answer = predict("Be concise", "What is 2+2?");
 * var page = fetch("https://api.example.com/data");
 * var rows = query("SELECT region, SUM(sales) FROM data GROUP BY region");
 * submit(answer);
 * }</pre>
 *
 * <p>The set of reachable methods must match the set of {@code HostFunction}s registered on the
 * parent. {@link #fetch} and {@link #query} return empty collections when the corresponding host
 * function is not registered — the underlying RPC call fails and is surfaced as an exception from
 * {@link JvmSandboxBootstrap#callHost}; this wrapper lets that propagate rather than silently
 * returning empty.
 */
public final class HostBridge {

  private HostBridge() {}

  /**
   * Call the predict host function with fresh model context.
   *
   * @param instructions system instructions for the model
   * @param input the user input to send to the model
   * @return the model's response text
   * @throws IllegalStateException if called outside a sandbox
   */
  public static String predict(String instructions, String input) {
    var bootstrap = requireBootstrap();
    var result =
        bootstrap.callHost("predict", Map.of("instructions", instructions, "input", input));
    if (result instanceof Map<?, ?> map) {
      var output = map.get("output");
      return output != null ? output.toString() : "";
    }
    return result != null ? result.toString() : "";
  }

  /**
   * Fetch a URL via the host's HTTP bridge. The host (caller-supplied) is expected to enforce a
   * domain allowlist, HTTPS-only, and response size caps.
   *
   * @param url the URL to fetch (must be HTTPS and on the host's allowlist)
   * @return a map with keys {@code status} (integer HTTP status code), {@code body} (response body
   *     as string), and {@code contentType} (response Content-Type header, may be empty)
   * @throws IllegalStateException if called outside a sandbox
   * @throws RuntimeException if the URL is rejected or the fetch fails (wraps the host error)
   */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> fetch(String url) {
    var bootstrap = requireBootstrap();
    var result = bootstrap.callHost("fetch", Map.of("url", url));
    if (result instanceof Map<?, ?> map) {
      return Collections.unmodifiableMap(new LinkedHashMap<>((Map<String, Object>) map));
    }
    return Map.of();
  }

  /**
   * Execute a read-only SQL query via the host's JDBC bridge. The host (caller-supplied) is
   * expected to reject anything that is not a {@code SELECT}/{@code WITH}/{@code EXPLAIN}/etc. and
   * to open the connection in read-only mode.
   *
   * @param sql the query text
   * @return rows as an immutable list of column-name→value maps; an empty list when the query
   *     returns no rows
   * @throws IllegalStateException if called outside a sandbox
   * @throws RuntimeException if the query is rejected or fails (wraps the host error)
   */
  @SuppressWarnings("unchecked")
  public static List<Map<String, Object>> query(String sql) {
    var bootstrap = requireBootstrap();
    var result = bootstrap.callHost("query", Map.of("sql", sql));
    if (result instanceof List<?> list) {
      var rows = new ArrayList<Map<String, Object>>(list.size());
      for (var row : list) {
        if (row instanceof Map<?, ?> m) {
          rows.add(Collections.unmodifiableMap(new LinkedHashMap<>((Map<String, Object>) m)));
        }
      }
      return Collections.unmodifiableList(rows);
    }
    return List.of();
  }

  /**
   * Fetch the harness-provided input record fields as a {@code Map<String, Object>}. Routed through
   * the JSON-RPC bridge to a host-registered {@code __getInput} function so the sandbox-side
   * snippet does not need to import or compile against any JSON library.
   *
   * <p>The returned map mirrors the user's input record's top-level components. {@code
   * InputBindings} emits JShell that reads from this map and casts each field to its declared type
   * — no Jackson reference appears in the JShell snippet.
   *
   * @return the input fields keyed by component name, or an empty map when the harness did not
   *     register an input
   */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> getInput() {
    var bootstrap = requireBootstrap();
    var result = bootstrap.callHost("__getInput", Map.of());
    if (result instanceof Map<?, ?> map) {
      return Collections.unmodifiableMap(new LinkedHashMap<>((Map<String, Object>) map));
    }
    return Map.of();
  }

  /**
   * Submit the final result from sandbox code.
   *
   * @param output the value to submit
   * @throws IllegalStateException if called outside a sandbox
   */
  public static void submit(Object output) {
    var bootstrap = requireBootstrap();
    bootstrap.callHost("submit", Map.of("output", output));
    bootstrap.setSubmittedValue(output);
  }

  /**
   * Internal relay for {@code SandboxPrelude}-synthesized wrappers around custom host functions.
   * The synthesizer emits a typed JShell wrapper per registered {@code HostFunction} (e.g. {@code
   * static Object marketQuote(String ticker)}); each wrapper packs its arguments into a {@code Map}
   * and calls this method, which forwards to the JSON-RPC channel.
   *
   * <p>Sandbox code should call the typed wrappers (e.g. {@code marketQuote("AAPL")}) — this
   * dispatcher is named {@code __call} on purpose so it's discoverable but not the obvious entry
   * point. Calling it directly skips the model's typed-signature contract.
   *
   * @param name the registered host function name
   * @param args the call arguments keyed by parameter name (caller supplies, may be empty)
   * @return whatever the handler returns
   * @throws IllegalStateException if called outside a sandbox
   */
  public static Object __call(String name, Map<String, Object> args) {
    var bootstrap = requireBootstrap();
    return bootstrap.callHost(name, args == null ? Map.of() : args);
  }

  private static JvmSandboxBootstrap requireBootstrap() {
    var bootstrap = JvmSandboxBootstrap.instance();
    if (bootstrap == null) {
      throw new IllegalStateException("HostBridge can only be called from within a sandbox");
    }
    return bootstrap;
  }
}
