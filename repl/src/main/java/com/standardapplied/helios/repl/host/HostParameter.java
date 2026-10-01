/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.host;

import ai.singlr.core.common.Strings;
import ai.singlr.core.tool.ParameterType;

/**
 * Declared parameter for a {@link HostFunction}.
 *
 * <p>The parameter list on a {@code HostFunction} drives JShell wrapper synthesis: every
 * non-reserved {@code HostFunction} the registry knows about gets a typed static method generated
 * into the sandbox's preamble at boot. With a parameter named {@code ticker} of type {@code STRING}
 * the model writes {@code marketQuote("AAPL")} from emitted Java code; the wrapper packs the
 * arguments into a {@code Map<String, Object>} keyed by parameter name and dispatches to the host.
 *
 * <p>Without declared parameters the synthesized wrapper is zero-arg ({@code static Object foo()})
 * and passes an empty map to the handler. That is rarely useful — declare parameters explicitly so
 * the model has a typed signature to call.
 *
 * @param name parameter name; must be a valid Java identifier (used as the method-parameter name in
 *     synthesized JShell)
 * @param type JSON Schema-style type, mapped to a Java type in the synthesized signature
 * @param description short description of the parameter; surfaced in {@code
 *     SandboxPrelude.modelFacingSummary} so the LLM knows what the parameter means
 * @param required whether the caller must supply the parameter; optional parameters are still
 *     present in the synthesized signature (with their boxed nullable type) — the contract is "the
 *     model passes {@code null} when omitting"
 */
public record HostParameter(String name, ParameterType type, String description, boolean required) {

  public HostParameter {
    if (Strings.isBlank(name)) {
      throw new IllegalArgumentException("Host parameter name must not be null or blank");
    }
    if (!HostIdentifiers.isValidJavaIdentifier(name)) {
      throw new IllegalArgumentException(
          "Host parameter name must be a valid Java identifier: " + name);
    }
    if (type == null) {
      throw new IllegalArgumentException("Host parameter type must not be null");
    }
    if (Strings.isBlank(description)) {
      throw new IllegalArgumentException("Host parameter description must not be null or blank");
    }
  }

  /** A required parameter. */
  public static HostParameter required(String name, ParameterType type, String description) {
    return new HostParameter(name, type, description, true);
  }

  /** An optional parameter. The synthesized signature accepts {@code null} for omission. */
  public static HostParameter optional(String name, ParameterType type, String description) {
    return new HostParameter(name, type, description, false);
  }

  /**
   * Java type used in the synthesized JShell wrapper for this parameter. Always boxed (so {@code
   * INTEGER} maps to {@link Long} not {@code long}) — primitive parameters can't accept {@code
   * null}, and we want optional parameters to be nullable without a separate signature.
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
