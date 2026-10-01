/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Controls how the model uses tools during generation.
 *
 * <p>Use the static factory methods to create instances:
 *
 * <ul>
 *   <li>{@link #auto()} - Model decides whether to use tools
 *   <li>{@link #any()} - Model must use at least one tool
 *   <li>{@link #none()} - Model cannot use any tools
 *   <li>{@link #required(String...)} - Model must use one of the specified tools
 * </ul>
 */
public sealed interface ToolChoice {

  /**
   * Model decides whether to use tools based on the prompt.
   *
   * @return auto tool choice
   */
  static ToolChoice auto() {
    return Auto.INSTANCE;
  }

  /**
   * Model must use at least one tool.
   *
   * @return any tool choice
   */
  static ToolChoice any() {
    return Any.INSTANCE;
  }

  /**
   * Model cannot use any tools.
   *
   * @return none tool choice
   */
  static ToolChoice none() {
    return None.INSTANCE;
  }

  /**
   * Model must use one of the specified tools.
   *
   * @param toolNames the names of the allowed tools
   * @return required tool choice with allowed tools
   */
  static ToolChoice required(String... toolNames) {
    return new Required(Set.copyOf(List.of(toolNames)));
  }

  /** Model decides whether to use tools. */
  record Auto() implements ToolChoice {
    static final Auto INSTANCE = new Auto();
  }

  /** Model must use at least one tool. */
  record Any() implements ToolChoice {
    static final Any INSTANCE = new Any();
  }

  /** Model cannot use any tools. */
  record None() implements ToolChoice {
    static final None INSTANCE = new None();
  }

  /**
   * Model must use one of the specified tools.
   *
   * @param allowedTools the names of the tools the model can use
   */
  record Required(Set<String> allowedTools) implements ToolChoice {
    public Required {
      Objects.requireNonNull(allowedTools, "allowedTools must not be null");
      if (allowedTools.isEmpty()) {
        throw new IllegalArgumentException("allowedTools must not be empty");
      }
    }
  }
}
