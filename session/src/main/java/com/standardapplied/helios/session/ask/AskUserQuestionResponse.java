/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.ask;

import ai.singlr.core.common.Strings;
import java.util.List;
import java.util.Objects;

/**
 * The user's answer to an {@link AskUserQuestionRequest}.
 *
 * <p>{@code selectedLabels} carries the labels the user chose. For a single-select question the
 * list contains exactly one entry; for multi-select it may contain one or more. A {@code
 * customText} field carries free-form input when the user picked the "Other" affordance — empty
 * string when no custom text was supplied.
 *
 * @param questionId the id of the question this response answers; non-blank
 * @param selectedLabels the labels the user selected; non-null, non-empty, defensively copied
 * @param customText optional free-text payload (e.g. the user's "Other" input); non-null, may be
 *     empty
 */
public record AskUserQuestionResponse(
    String questionId, List<String> selectedLabels, String customText) {

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if any argument is null
   * @throws IllegalArgumentException if {@code questionId} is blank, {@code selectedLabels} is
   *     empty, or any selected label is blank
   */
  public AskUserQuestionResponse {
    Objects.requireNonNull(questionId, "questionId must not be null");
    if (Strings.isBlank(questionId)) {
      throw new IllegalArgumentException("questionId must not be blank");
    }
    Objects.requireNonNull(selectedLabels, "selectedLabels must not be null");
    if (selectedLabels.isEmpty()) {
      throw new IllegalArgumentException("selectedLabels must not be empty");
    }
    for (var l : selectedLabels) {
      Objects.requireNonNull(l, "selectedLabels must not contain null");
      if (Strings.isBlank(l)) {
        throw new IllegalArgumentException("selectedLabels must not contain blank entries");
      }
    }
    selectedLabels = List.copyOf(selectedLabels);
    Objects.requireNonNull(customText, "customText must not be null");
  }

  /**
   * Convenience factory for a single-selection response with no custom text.
   *
   * @param questionId the question id; non-blank
   * @param label the selected label; non-blank
   * @return a fresh response
   */
  public static AskUserQuestionResponse single(String questionId, String label) {
    return new AskUserQuestionResponse(questionId, List.of(label), "");
  }
}
