/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.host;

import ai.singlr.core.common.Strings;
import java.util.List;

/**
 * A named host function that sandbox code can call from emitted JShell.
 *
 * <p>Every non-reserved {@code HostFunction} registered before sandbox boot gets a typed JShell
 * wrapper synthesized into the sandbox preamble: a {@code HostFunction} named {@code marketQuote}
 * with parameters {@code [ticker: STRING]} becomes callable as {@code marketQuote("AAPL")} from any
 * {@code execute_code} call. The wrapper packs arguments into a {@code Map<String, Object>} keyed
 * by parameter name and dispatches to this {@link #handler}.
 *
 * <p>Functions declared with an empty {@link #parameters()} list still get a synthesized wrapper —
 * a zero-arg one — and the handler receives an empty map. Almost always you want to declare the
 * parameters explicitly so the model sees a typed signature.
 *
 * <p>Reserved names ({@code predict}, {@code submit}, {@code fetch}, {@code query}, {@code
 * getInput}, {@code __getInput}, {@code __call}) are skipped by the synthesizer because hardcoded
 * static methods on {@code HostBridge} already provide them.
 *
 * @param name function name; used as the JSON-RPC method and as the synthesized JShell method name.
 *     Must be a valid Java identifier
 * @param description human-readable description; surfaced in the LLM's system prompt
 * @param parameters declared parameter list driving wrapper synthesis (immutable copy taken)
 * @param handler the implementation
 */
public record HostFunction(
    String name, String description, List<HostParameter> parameters, HostFunctionHandler handler) {

  public HostFunction {
    if (Strings.isBlank(name)) {
      throw new IllegalArgumentException("Host function name must not be null or blank");
    }
    if (!HostIdentifiers.isValidJavaIdentifier(name)) {
      throw new IllegalArgumentException(
          "Host function name must be a valid Java identifier: " + name);
    }
    if (Strings.isBlank(description)) {
      throw new IllegalArgumentException("Host function description must not be null or blank");
    }
    if (handler == null) {
      throw new IllegalArgumentException("Host function handler must not be null");
    }
    parameters = parameters == null ? List.of() : List.copyOf(parameters);
  }

  /** Convenience constructor for a function with no declared parameters. */
  public HostFunction(String name, String description, HostFunctionHandler handler) {
    this(name, description, List.of(), handler);
  }
}
