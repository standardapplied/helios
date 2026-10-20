/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.tools;

import com.standardapplied.helios.core.tool.ToolContext;
import java.util.Objects;

/**
 * The one filter a tool that walks below its authorised path applies to each entry it would return:
 * an entry whose canonical key the call's policy withholds (a DENY rule of the walking tool, or a
 * DENY or ASK rule of a tool whose content it returns, decides the key) is skipped and counted, and
 * the count ends the tool's output as a fixed line that names no entry. One per call, confined to
 * the walk's thread.
 */
public final class Withholding {

  private final ToolContext context;
  private int withheld;

  private Withholding(ToolContext context) {
    this.context = context;
  }

  /**
   * A filter for one call.
   *
   * @param context the call's context; non-null
   * @return a fresh filter that has withheld nothing
   * @throws NullPointerException if {@code context} is null
   */
  public static Withholding of(ToolContext context) {
    return new Withholding(Objects.requireNonNull(context, "context must not be null"));
  }

  /**
   * Whether to skip the entry with canonical key {@code canonicalKey}; a skipped entry is counted.
   *
   * @param canonicalKey the entry's canonical permission key; non-null
   * @return {@code true} when the call's policy denies the entry
   */
  public boolean withholds(String canonicalKey) {
    if (!context.withholds(canonicalKey)) {
      return false;
    }
    withheld++;
    return true;
  }

  /**
   * The line that ends the tool's output.
   *
   * @return {@code [withheld by permission policy: <count>]} and a newline, or {@code ""} when
   *     nothing was withheld
   */
  public String note() {
    return withheld == 0 ? "" : "[withheld by permission policy: " + withheld + "]\n";
  }
}
