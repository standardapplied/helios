/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import com.standardapplied.helios.core.common.Validate;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A child agent the model of a session can delegate to through the built-in {@code Agent} tool,
 * registered with {@link SessionOptions.Builder#withSubagent}. Each {@code Agent} call naming it
 * runs one fresh child session from {@code options}, under a fresh {@code sess-} id and with {@code
 * name} as its {@linkplain SessionOptions#agentName() agent name}, sends it the call's prompt,
 * waits for its run to end and closes it. Nothing is inherited from the parent: {@code options} is
 * the child's whole composition, its model, system prompt, tools, permission, hooks, limits, output
 * schema and store included.
 *
 * <p>A child has no one to answer a question: a call its permission policy answers with {@code ASK}
 * is denied, with a reason its model reads, so allow in {@code options}' permission every tool the
 * child needs.
 *
 * @param name the name the parent's model calls it by, matching {@code [a-z][a-z0-9_-]{0,63}}
 * @param description what it does, shown to the parent's model in the {@code Agent} tool's
 *     description; non-blank
 * @param options the child session's composition; non-null, without {@code AskUserQuestion}
 */
public record Subagent(String name, String description, SessionOptions options) {

  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_-]{0,63}");

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if any argument is null
   * @throws IllegalArgumentException if {@code name} breaks the naming rule, {@code description} is
   *     blank, or {@code options} registers {@code AskUserQuestion}
   */
  public Subagent {
    Objects.requireNonNull(name, "name must not be null");
    if (!NAME.matcher(name).matches()) {
      throw new IllegalArgumentException(
          "subagent name \"" + name + "\" must match " + NAME.pattern());
    }
    Validate.notBlank("description", description);
    Objects.requireNonNull(options, "options must not be null");
    if (options.askUserQuestion()) {
      throw new IllegalArgumentException(
          "subagent \"" + name + "\" registers AskUserQuestion, but a subagent has no one to ask");
    }
  }
}
