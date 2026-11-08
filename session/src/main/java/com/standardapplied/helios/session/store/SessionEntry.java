/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.store;

import com.standardapplied.helios.core.common.Validate;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.session.ResultMessage;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One entry of a session's stored log, written by the run {@link #runId()} at {@link #at()}: a
 * message the model received, a replacement of the model's whole view, or the end of the run. A
 * later kind is a new subtype, which a reader that does not know it refuses.
 */
public sealed interface SessionEntry {

  /**
   * The run that wrote the entry.
   *
   * @return non-blank run id
   */
  String runId();

  /**
   * When the entry was written, on the session's clock.
   *
   * @return non-null instant
   */
  Instant at();

  /**
   * One message added to the conversation, exactly as the model received it.
   *
   * @param runId the writing run; non-blank
   * @param at when it was written; non-null
   * @param message the message; non-null
   */
  record MessageAdded(String runId, Instant at, Message message) implements SessionEntry {

    /**
     * Validates the entry.
     *
     * @throws NullPointerException if a component is null
     * @throws IllegalArgumentException if {@code runId} is blank
     */
    public MessageAdded {
      Validate.notBlank("runId", runId);
      Objects.requireNonNull(at, "at must not be null");
      Objects.requireNonNull(message, "message must not be null");
    }
  }

  /**
   * The model's view replaced wholesale, by compaction or a {@code MutateHistory} hook: {@code
   * history} is the new view, without the system prompt.
   *
   * @param runId the writing run; non-blank
   * @param at when it was written; non-null
   * @param history the new view; copied, no null element
   */
  record HistoryReplaced(String runId, Instant at, List<Message> history) implements SessionEntry {

    /**
     * Validates the entry and copies {@code history}.
     *
     * @throws NullPointerException if a component is null or {@code history} holds a null
     * @throws IllegalArgumentException if {@code runId} is blank
     */
    public HistoryReplaced {
      Validate.notBlank("runId", runId);
      Objects.requireNonNull(at, "at must not be null");
      history = List.copyOf(Objects.requireNonNull(history, "history must not be null"));
    }
  }

  /**
   * The end of run {@code runId}, appended in the same batch as the last messages the run added, so
   * a store holds both or neither. {@code terminal} names the run's terminal as the run decided it.
   * A store that refuses that batch holds no {@code RunEnded} for the run, whose terminal is then
   * the refusal. A host {@link Error} recorded after the batch was written, while the run waits for
   * its tool calls to stop, fails the run's future but leaves this entry as written. An {@code
   * Error} thrown on the run's own thread ends it before its end, so it writes none; one the
   * session records from another thread cancels the run, which still writes this entry while its
   * future fails with the {@code Error}.
   *
   * @param runId the run that ended; non-blank
   * @param at when it ended; non-null
   * @param terminal the simple name of the run's {@link ResultMessage} record, such as {@code
   *     Success} or {@code ErrorMaxTurns}, the spelling a run's span records as {@code
   *     helios.terminal}
   * @param result the run's answer, {@link ResultMessage.Success#result()} as the run returned it,
   *     whether the model's reply, a hook's {@code Stop} text or empty; present exactly when {@code
   *     terminal} is {@code Success}; non-null
   */
  record RunEnded(String runId, Instant at, String terminal, Optional<String> result)
      implements SessionEntry {

    private static final String SUCCESS = ResultMessage.Success.class.getSimpleName();

    private static final List<String> TERMINALS =
        Arrays.stream(ResultMessage.class.getPermittedSubclasses())
            .map(Class::getSimpleName)
            .sorted()
            .toList();

    /**
     * Validates the entry.
     *
     * @throws NullPointerException if a component is null
     * @throws IllegalArgumentException if {@code runId} is blank, {@code terminal} names no {@link
     *     ResultMessage} subtype, or {@code result} is present on any terminal but {@code Success}
     *     or empty on {@code Success}
     */
    public RunEnded {
      Validate.notBlank("runId", runId);
      Objects.requireNonNull(at, "at must not be null");
      Objects.requireNonNull(terminal, "terminal must not be null");
      Objects.requireNonNull(result, "result must not be null");
      if (!TERMINALS.contains(terminal)) {
        throw new IllegalArgumentException(
            "terminal must name a ResultMessage subtype " + TERMINALS + ", got '" + terminal + "'");
      }
      if (terminal.equals(SUCCESS) != result.isPresent()) {
        throw new IllegalArgumentException(
            "result must be present exactly when terminal is Success, got "
                + (result.isPresent() ? "one" : "none")
                + " on "
                + terminal);
      }
    }

    /**
     * The end of run {@code runId} with {@code terminal}.
     *
     * @param runId the run that ended; non-blank
     * @param at when it ended; non-null
     * @param terminal the run's terminal, whose result the entry keeps when it is a {@code
     *     Success}; non-null
     * @return the entry
     * @throws NullPointerException if an argument is null
     * @throws IllegalArgumentException if {@code runId} is blank
     */
    public static RunEnded of(String runId, Instant at, ResultMessage terminal) {
      var result =
          terminal instanceof ResultMessage.Success success
              ? Optional.of(success.result())
              : Optional.<String>empty();
      return new RunEnded(runId, at, terminal.getClass().getSimpleName(), result);
    }
  }
}
