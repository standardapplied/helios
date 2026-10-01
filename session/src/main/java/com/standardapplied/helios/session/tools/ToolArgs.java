/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.tools;

import java.util.Map;

/**
 * Type-coercion helpers shared by every built-in tool's executor.
 *
 * <p>Tool inputs arrive as {@code Map<String, Object>} where each provider may decode JSON numbers
 * as {@code Integer}, {@code Long}, or {@code Double}, and where missing keys are normal.
 * Centralising the coercion logic here means every tool gets the same forgiving extraction without
 * each one re-implementing four near-identical helper methods.
 *
 * <p>All extractors are null-safe and treat type-mismatched values as "missing" — they return the
 * default rather than throwing, so the tool executor can decide how to surface "missing required
 * argument" with the tool-specific error string.
 */
public final class ToolArgs {

  private ToolArgs() {}

  /**
   * Extract a string argument by name.
   *
   * @param args the tool's argument map; non-null
   * @param name the argument name; non-null
   * @param defaultValue value returned when the key is absent or not a {@code String}
   * @return the bound string or {@code defaultValue}
   */
  public static String stringArg(Map<String, Object> args, String name, String defaultValue) {
    var v = args.get(name);
    return v instanceof String s ? s : defaultValue;
  }

  /**
   * Extract a string argument that defaults to the empty string. Useful for "required, surfaced as
   * tool error if missing" args — the executor compares {@code stringArg(args, "x")} against {@code
   * ""} or uses {@link ai.singlr.core.common.Strings#isBlank(String) Strings.isBlank} to decide.
   *
   * @param args the tool's argument map; non-null
   * @param name the argument name; non-null
   * @return the bound string or {@code ""}
   */
  public static String stringArg(Map<String, Object> args, String name) {
    return stringArg(args, name, "");
  }

  /**
   * Extract a string argument with a {@code null} fallback. Useful when the executor wants to
   * distinguish "missing" from "empty string supplied by the model".
   *
   * @param args the tool's argument map; non-null
   * @param name the argument name; non-null
   * @return the bound string, or {@code null} if absent or not a string
   */
  public static String stringArgOrNull(Map<String, Object> args, String name) {
    return stringArg(args, name, null);
  }

  /**
   * Extract a "path" argument — by convention the {@code path} key, defaulting to {@code "."} when
   * absent or empty. Every file tool reaches for this shape.
   *
   * @param args the tool's argument map; non-null
   * @return the path, or {@code "."} when missing or empty
   */
  public static String pathArg(Map<String, Object> args) {
    return pathArg(args, ".");
  }

  /**
   * Extract a "path" argument with a caller-supplied default.
   *
   * @param args the tool's argument map; non-null
   * @param defaultValue value returned when the {@code path} key is absent or empty
   * @return the path or {@code defaultValue}
   */
  public static String pathArg(Map<String, Object> args, String defaultValue) {
    var v = args.get("path");
    return v instanceof String s && !s.isEmpty() ? s : defaultValue;
  }

  /**
   * Extract an integer argument. Coerces {@code Integer}, {@code Long}, and any other {@link
   * Number} subclass; returns the default for missing or non-numeric values.
   *
   * @param args the tool's argument map; non-null
   * @param name the argument name; non-null
   * @param defaultValue value returned when the key is absent or not numeric
   * @return the bound int or {@code defaultValue}
   * @throws ArithmeticException if the bound value is a {@code Long} that does not fit in an int
   */
  public static int intArg(Map<String, Object> args, String name, int defaultValue) {
    var v = args.get(name);
    if (v instanceof Integer i) {
      return i;
    }
    if (v instanceof Long l) {
      return Math.toIntExact(l);
    }
    if (v instanceof Number n) {
      return n.intValue();
    }
    return defaultValue;
  }

  /**
   * Extract a boolean argument.
   *
   * @param args the tool's argument map; non-null
   * @param name the argument name; non-null
   * @param defaultValue value returned when the key is absent or not a {@code Boolean}
   * @return the bound boolean or {@code defaultValue}
   */
  public static boolean boolArg(Map<String, Object> args, String name, boolean defaultValue) {
    var v = args.get(name);
    if (v instanceof Boolean b) {
      return b;
    }
    return defaultValue;
  }
}
