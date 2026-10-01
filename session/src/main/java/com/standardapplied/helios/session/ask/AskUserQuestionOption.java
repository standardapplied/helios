/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.ask;

import ai.singlr.core.common.Strings;
import java.util.Objects;

/**
 * One selectable choice in an {@link AskUserQuestionRequest}.
 *
 * @param label the short label shown to the user; non-blank, 1–80 characters
 * @param description the explanatory description shown alongside the label; non-null, may be empty
 */
public record AskUserQuestionOption(String label, String description) {

  /** Max length for {@link #label}. */
  public static final int MAX_LABEL_LENGTH = 80;

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if any argument is null
   * @throws IllegalArgumentException if {@code label} is blank or longer than {@link
   *     #MAX_LABEL_LENGTH}
   */
  public AskUserQuestionOption {
    Objects.requireNonNull(label, "label must not be null");
    if (Strings.isBlank(label)) {
      throw new IllegalArgumentException("label must not be blank");
    }
    if (label.length() > MAX_LABEL_LENGTH) {
      throw new IllegalArgumentException(
          "label must be at most " + MAX_LABEL_LENGTH + " chars, got " + label.length());
    }
    Objects.requireNonNull(description, "description must not be null");
  }

  /**
   * Convenience factory for an option with no description.
   *
   * @param label non-blank label
   * @return a fresh option
   */
  public static AskUserQuestionOption of(String label) {
    return new AskUserQuestionOption(label, "");
  }
}
