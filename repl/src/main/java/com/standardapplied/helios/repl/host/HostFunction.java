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
 * @param description human-readable description; surfaced in the LLM's system prompt; must not be
 *     {@code null} or blank
 * @param parameters declared parameter list driving wrapper synthesis (immutable copy taken); no
 *     two parameters share a name. {@code null} is stored as an empty list
 * @param handler the implementation, called on the host with the arguments keyed by parameter name;
 *     must not be {@code null}
 */
public record HostFunction(
    String name, String description, List<HostParameter> parameters, HostFunctionHandler handler) {

  /**
   * Validates the components and stores an immutable copy of the parameters.
   *
   * @throws IllegalArgumentException if {@code name} is not a valid host identifier or is {@code
   *     yield}, if {@code description} is {@code null} or blank, if {@code handler} is {@code
   *     null}, or if two parameters share a name
   * @throws NullPointerException if {@code parameters} holds a {@code null} element
   */
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

  /**
   * Convenience constructor for a function with no declared parameters, whose synthesized wrapper
   * is zero-arg and whose handler receives an empty map.
   *
   * @param name the function name, under the same rules as the canonical constructor
   * @param description human-readable description; must not be {@code null} or blank
   * @param handler the implementation; must not be {@code null}
   * @throws IllegalArgumentException if any argument fails the canonical constructor's validation
   */
  public HostFunction(String name, String description, HostFunctionHandler handler) {
    this(name, description, List.of(), handler);
  }
}
