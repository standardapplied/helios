/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.repl.host;

import com.standardapplied.helios.core.common.Strings;
import java.util.HashSet;
import java.util.List;

/**
 * A named host function that sandbox code can call from emitted JShell.
 *
 * <p>Every {@code HostFunction} registered before sandbox boot gets a typed JShell wrapper
 * synthesized into the sandbox preamble: a {@code HostFunction} named {@code marketQuote} with
 * parameters {@code [ticker: STRING]} becomes callable as {@code marketQuote("AAPL")} from any
 * {@code Execute} call. The wrapper packs arguments into a {@code Map<String, Object>} keyed by
 * parameter name and dispatches to this {@link #handler}.
 *
 * <p>Functions declared with an empty {@link #parameters()} list still get a synthesized wrapper —
 * a zero-arg one — and the handler receives an empty map. Almost always you want to declare the
 * parameters explicitly so the model sees a typed signature.
 *
 * @param name function name; used as the JSON-RPC method and as the synthesized JShell method name.
 *     Must be a Java identifier, not a keyword or literal, not {@code yield} (which Java does not
 *     accept as an unqualified call) and not start with {@code __}, so no host function shadows
 *     {@code HostBridge.__call}
 * @param description human-readable description; surfaced in the LLM's system prompt
 * @param parameters declared parameter list driving wrapper synthesis (immutable copy taken); no
 *     two parameters share a name
 * @param handler the implementation
 */
public record HostFunction(
    String name, String description, List<HostParameter> parameters, HostFunctionHandler handler) {

  public HostFunction {
    HostIdentifiers.requireValid(name, "Host function name");
    if (name.equals("yield")) {
      throw new IllegalArgumentException(
          "Host function name must not be yield, which Java does not accept as an unqualified call");
    }
    if (Strings.isBlank(description)) {
      throw new IllegalArgumentException("Host function description must not be null or blank");
    }
    if (handler == null) {
      throw new IllegalArgumentException("Host function handler must not be null");
    }
    parameters = parameters == null ? List.of() : List.copyOf(parameters);
    var names = new HashSet<String>();
    for (var parameter : parameters) {
      if (!names.add(parameter.name())) {
        throw new IllegalArgumentException(
            "Host function " + name + " declares parameter " + parameter.name() + " twice");
      }
    }
  }

  /** Convenience constructor for a function with no declared parameters. */
  public HostFunction(String name, String description, HostFunctionHandler handler) {
    this(name, description, List.of(), handler);
  }
}
