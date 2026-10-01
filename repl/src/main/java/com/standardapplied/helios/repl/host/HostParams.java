/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.host;

import java.util.Map;

/** Shared parameter helpers for {@link HostFunction} handlers. */
final class HostParams {

  private HostParams() {}

  /**
   * Extract a required string parameter from a handler's argument map.
   *
   * @param params the parameter map passed to the handler
   * @param key the parameter name
   * @return the string value
   * @throws IllegalArgumentException if the parameter is missing or not a string
   */
  static String requireString(Map<String, Object> params, String key) {
    if (params.get(key) instanceof String s) {
      return s;
    }
    throw new IllegalArgumentException("Parameter '" + key + "' is required and must be a string");
  }
}
