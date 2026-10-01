/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.ask;

import ai.singlr.core.common.Strings;
import java.util.List;
import java.util.Objects;

/**
 * A structured question the agent emits to the user.
 *
 * <p>Carried by {@link ai.singlr.session.QueryEvent.QuestionAsked QueryEvent.QuestionAsked} and by
 * the {@code AskUserQuestion} tool's request payload. The session blocks the tool's executing
 * virtual thread on a future keyed by {@code questionId}; the host completes that future by calling
 * {@link ai.singlr.session.AgentSession#answer(String, AskUserQuestionResponse)
 * AgentSession.answer}.
 *
 * @param questionId opaque, unique-per-session id used to correlate the answer back to the
 *     question; non-blank
 * @param header short chip-style label (max 12 chars) describing the question subject; non-null,
 *     may be empty for no chip
 * @param question the full question text shown to the user; non-blank
 * @param options the available choices; non-null, defensively copied, must contain 2–4 entries
 * @param multiSelect {@code true} if the user may select multiple options
 */
public record AskUserQuestionRequest(
    String questionId,
    String header,
    String question,
    List<AskUserQuestionOption> options,
    boolean multiSelect) {

  /** The maximum header length the spec allows. */
  public static final int MAX_HEADER_LENGTH = 12;

  /** The minimum options count. */
  public static final int MIN_OPTIONS = 2;

  /** The maximum options count. */
  public static final int MAX_OPTIONS = 4;

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if any argument is null
   * @throws IllegalArgumentException if {@code questionId} is blank, {@code header} is longer than
   *     {@link #MAX_HEADER_LENGTH}, {@code question} is blank, or {@code options} does not contain
   *     between {@link #MIN_OPTIONS} and {@link #MAX_OPTIONS} entries
   */
  public AskUserQuestionRequest {
    Objects.requireNonNull(questionId, "questionId must not be null");
    if (Strings.isBlank(questionId)) {
      throw new IllegalArgumentException("questionId must not be blank");
    }
    Objects.requireNonNull(header, "header must not be null");
    if (header.length() > MAX_HEADER_LENGTH) {
      throw new IllegalArgumentException(
          "header must be at most " + MAX_HEADER_LENGTH + " chars, got " + header.length());
    }
    Objects.requireNonNull(question, "question must not be null");
    if (Strings.isBlank(question)) {
      throw new IllegalArgumentException("question must not be blank");
    }
    Objects.requireNonNull(options, "options must not be null");
    if (options.size() < MIN_OPTIONS || options.size() > MAX_OPTIONS) {
      throw new IllegalArgumentException(
          "options must contain between "
              + MIN_OPTIONS
              + " and "
              + MAX_OPTIONS
              + " entries, got "
              + options.size());
    }
    for (var o : options) {
      Objects.requireNonNull(o, "options must not contain null");
    }
    options = List.copyOf(options);
  }
}
