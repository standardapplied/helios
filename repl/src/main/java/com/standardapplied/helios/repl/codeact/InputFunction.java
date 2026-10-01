/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.repl.codeact;

import ai.singlr.repl.host.HostFunction;
import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * In-sandbox {@code __getInput} host function: returns the user-supplied input record as a {@code
 * Map<String, Object>} so {@link ai.singlr.repl.sandbox.HostBridge#getInput()} (and the {@link
 * ai.singlr.repl.InputBindings} snippet generated from the input type) can read it.
 *
 * <p>{@code __getInput} is one of the framework-reserved host-function names — its presence in the
 * sandbox registry is what makes the typed input variables show up in the JShell session at the top
 * of the first {@code execute_code} call. Without it, {@code HostBridge.getInput()} returns an
 * empty map and the bindings snippet binds {@code null} for every field.
 */
public final class InputFunction {

  /** Reserved host-function name; matches {@link ai.singlr.repl.sandbox.HostBridge#getInput()}. */
  public static final String NAME = "__getInput";

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private InputFunction() {}

  /**
   * Build the {@code __getInput} host function. The {@code input} record is serialized lazily on
   * each invocation; for most cases there is exactly one invocation per session (the {@link
   * ai.singlr.repl.InputBindings}-generated snippet at first execute_code).
   *
   * @param input the user's typed input record, or {@code null} to expose an empty map
   * @return a host function that returns the input fields as a Map
   */
  public static HostFunction create(Object input) {
    return new HostFunction(
        NAME,
        "Framework: returns the per-session input record as a Map. Called by HostBridge.getInput()"
            + " on the model's behalf via the InputBindings snippet.",
        List.of(),
        params -> toMap(input));
  }

  private static Map<String, Object> toMap(Object input) {
    if (input == null) {
      return Map.of();
    }
    if (input instanceof Map<?, ?> map) {
      @SuppressWarnings({"unchecked", "rawtypes"})
      var typed = (Map<String, Object>) (Map) map;
      return typed;
    }
    return MAPPER.convertValue(input, new TypeReference<Map<String, Object>>() {});
  }
}
