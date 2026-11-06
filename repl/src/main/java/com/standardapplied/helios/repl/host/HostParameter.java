/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.repl.host;

import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.tool.ParameterType;

/**
 * Declared parameter for a {@link HostFunction}.
 *
 * <p>The parameter list on a {@code HostFunction} drives JShell wrapper synthesis: every {@code
 * HostFunction} the registry knows about gets a typed static method generated into the sandbox's
 * preamble at boot. With a parameter named {@code ticker} of type {@code STRING} the model writes
 * {@code marketQuote("AAPL")} from emitted Java code; the wrapper packs the arguments into a {@code
 * Map<String, Object>} keyed by parameter name and dispatches to the host.
 *
 * <p>Without declared parameters the synthesized wrapper is zero-arg ({@code static Object foo()})
 * and passes an empty map to the handler. That is rarely useful — declare parameters explicitly so
 * the model has a typed signature to call.
 *
 * @param name parameter name, used as the method-parameter name in synthesized JShell; must be a
 *     Java identifier, not a keyword or literal, and not start with {@code __}, the prefix of the
 *     wrapper's own locals
 * @param type JSON Schema-style type, mapped to a Java type in the synthesized signature; must not
 *     be {@code null}
 * @param description short description of the parameter; surfaced in {@code
 *     SandboxPrelude.customWrapperSummary} so the LLM knows what the parameter means; must not be
 *     {@code null} or blank
 * @param required whether the caller must supply the parameter; optional parameters are still
 *     present in the synthesized signature (with their boxed nullable type) — the contract is "the
 *     model passes {@code null} when omitting"
 */
public record HostParameter(String name, ParameterType type, String description, boolean required) {

  /**
   * Validates the components.
   *
   * @throws IllegalArgumentException if {@code name} is not a valid host identifier, {@code type}
   *     is {@code null}, or {@code description} is {@code null} or blank
   */
  public HostParameter {
    HostIdentifiers.requireValid(name, "Host parameter name");
    if (type == null) {
      throw new IllegalArgumentException("Host parameter type must not be null");
    }
    if (Strings.isBlank(description)) {
      throw new IllegalArgumentException("Host parameter description must not be null or blank");
    }
  }

  /**
   * Creates a parameter the caller must supply.
   *
   * @param name the parameter name, under the rules of the record's {@code name}
   * @param type the parameter's type; must not be {@code null}
   * @param description what the parameter means; must not be {@code null} or blank
   * @return a parameter with {@code required} set
   * @throws IllegalArgumentException if any argument fails the canonical constructor's validation
   */
  public static HostParameter required(String name, ParameterType type, String description) {
    return new HostParameter(name, type, description, true);
  }

  /**
   * Creates an optional parameter. The synthesized signature accepts {@code null} for omission.
   *
   * @param name the parameter name, under the rules of the record's {@code name}
   * @param type the parameter's type; must not be {@code null}
   * @param description what the parameter means; must not be {@code null} or blank
   * @return a parameter with {@code required} cleared
   * @throws IllegalArgumentException if any argument fails the canonical constructor's validation
   */
  public static HostParameter optional(String name, ParameterType type, String description) {
    return new HostParameter(name, type, description, false);
  }

  /**
   * Java type used in the synthesized JShell wrapper for this parameter. Always boxed (so {@code
   * INTEGER} maps to {@link Long} not {@code long}) — primitive parameters can't accept {@code
   * null}, and we want optional parameters to be nullable without a separate signature.
   *
   * @return the fully-qualified Java type, such as {@code java.lang.Long} for {@code INTEGER} or
   *     {@code java.util.Map<java.lang.String, java.lang.Object>} for {@code OBJECT}
   */
  public String javaType() {
    return switch (type) {
      case STRING -> "java.lang.String";
      case INTEGER -> "java.lang.Long";
      case NUMBER -> "java.lang.Double";
      case BOOLEAN -> "java.lang.Boolean";
      case ARRAY -> "java.util.List<java.lang.Object>";
      case OBJECT -> "java.util.Map<java.lang.String, java.lang.Object>";
    };
  }
}
