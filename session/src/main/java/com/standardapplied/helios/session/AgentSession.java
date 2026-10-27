/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session;

import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.session.ask.AskUserQuestionRequest;
import com.standardapplied.helios.session.ask.AskUserQuestionResponse;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * A conversation with an agent that runs many times.
 *
 * <h2>Vocabulary</h2>
 *
 * A <em>session</em> is the conversation: {@link #open} opens it, its id is the conversation's id,
 * and it holds the history, the hooks, the permission policy and its approvals, the secret
 * registry, the execution provider and the memory backend until it is closed. A <em>run</em> is one
 * execution: the user message that starts it, then every turn until a terminal {@link
 * ResultMessage}. A <em>turn</em> is one model call and its tool round. Every run sees the whole
 * conversation; {@code maxTurns}, {@code maxBudgetMicroUsd} and {@code maxWallClock} count per run.
 *
 * <h2>Lifecycle</h2>
 *
 * <pre>{@code
 * try (var session = AgentSession.open(options)) {
 *   var first = session.send("What changed in the build?").join();
 *   var second = session.send("Summarise that for the changelog.").join();
 * }
 * }</pre>
 *
 * <p>{@link #send} steers the run in progress, or starts the next run when none is in progress, and
 * returns that run's result future. A session runs again after any ending (success, a limit, a
 * cancel, an error), any number of times, until it is closed. {@link #interrupt()} cancels the run
 * in progress and keeps the session. {@link #events()} is one stream per reader across every run:
 * {@link QueryEvent.RunStarted} and {@link QueryEvent.RunEnded} bracket each run and every event
 * names its run. {@link #close()} ends the run in progress and releases the session.
 *
 * <h2>Thread-safety</h2>
 *
 * Implementations must be safe for the standard "many producers, one loop" pattern: HTTP/UI threads
 * call {@code send} / {@code interrupt} / {@code close} concurrently; each run's loop thread reads
 * the queue and offers each event to every open {@link EventStream}; readers drain them on their
 * own threads.
 */
public interface AgentSession extends AutoCloseable {

  /**
   * Open a session from the given options. For a session with an execution provider ({@code
   * SessionOptions.Builder#withExecutionProvider}), its {@code onSessionStart} runs before this
   * returns, within {@link SessionLimits#startTimeout()}. The session has no run until the first
   * {@link #send}.
   *
   * @param options the composition record; non-null
   * @return an open session with no run
   * @throws NullPointerException if {@code options} is null
   * @throws SessionUnavailableException if the execution provider refused, returned {@code null},
   *     threw an {@link Exception} or did not return in time; nothing stays acquired
   * @throws Error an {@link Error} {@code onSessionStart} threw, as itself
   */
  static AgentSession open(SessionOptions options) {
    Objects.requireNonNull(options, "options must not be null");
    return new AgentSessionImpl(options);
  }

  /**
   * Send a user message. While a run is in progress and has not decided to end, the message is
   * queued for it and joins it at its next iteration boundary; otherwise the message starts the
   * next run, at once when no run is in progress, or as soon as the run that decided to end has
   * ended. A message queued for a run that then ends before draining it (a limit, a hook {@code
   * Stop}, an error, an interrupt) is discarded with that run. A message is part of a run's input
   * once its {@link QueryEvent.UserMessageReceived} was emitted.
   *
   * <p>The future completes, after the run's {@link QueryEvent.RunEnded}, normally with the run's
   * terminal value. It completes exceptionally only with an {@link Error}, as itself: the first
   * caught on a thread the session runs work on (the loop, the model stream, a tool, a hook, a
   * cancellation callback, the execution provider's end). That is a host failure, not a terminal:
   * it ends the session as {@link #close()} does, every later {@code Error}, and an exception of
   * {@code onSessionEnd}, rides along as {@linkplain Throwable#getSuppressed() suppressed}, and the
   * run's thread ends by throwing it to its uncaught-exception handler; nothing stops the JVM. The
   * future of a next run the session's close prevents from starting is cancelled. Every message of
   * one run returns the same future, so completing or cancelling it settles it for every caller but
   * does not stop the run; {@link #interrupt()} does.
   *
   * @param message the message; non-null
   * @return the future of the run the message is part of
   * @throws NullPointerException if {@code message} is null
   * @throws IllegalStateException if the session is closed, or a host {@link Error} failed it
   * @throws SteeringQueueFullException if the steering queue is full; the message is not queued
   */
  CompletableFuture<ResultMessage> send(UserMessage message);

  /**
   * Convenience for {@code send(UserMessage.text(text))}.
   *
   * @param text non-null, non-blank text
   * @return the future of the run the message is part of
   */
  default CompletableFuture<ResultMessage> send(String text) {
    return send(UserMessage.text(text));
  }

  /**
   * Cancel the run in progress: it ends in {@link ResultMessage.Cancelled} with the reason {@code
   * "interrupted"}, its model stream closed and its tool calls cancelled, and messages still queued
   * for it are discarded. The session stays open, and the next {@link #send} starts a new run. With
   * no run in progress, or on a closed session, it does nothing. Never blocks, never throws.
   */
  void interrupt();

  /**
   * Open a new stream of session events, attached before this returns, that spans every run of the
   * session from now on and ends once the session is closed and its buffered events are read. Open
   * it before a {@link #send(UserMessage)} to observe every event of that run, and read it on a
   * thread of its own:
   *
   * <pre>{@code
   * try (var events = session.events()) {
   *   for (var event : events) {
   *     ...
   *   }
   * }
   * }</pre>
   *
   * <p>Each stream has its own bounded buffer. A routine event never waits for a reader: a stream
   * whose buffer is full misses it, so a slow reader never stalls the loop. See {@link EventStream}
   * for the buffer, the drop rule, the end and the late reader.
   *
   * @return a new, single-use stream
   */
  EventStream events();

  /**
   * The stable session identifier.
   *
   * @return non-blank id
   */
  String sessionId();

  /**
   * The turn index of the run in progress. Reads the live state; subject to change between calls.
   *
   * @return the run's turn index (its first turn is 1), {@code 0} when no run is in progress
   */
  long currentTurnIndex();

  /**
   * Whether a run is in progress: from the {@link #send(UserMessage)} that started it until its
   * future has completed and the session has left it.
   *
   * @return {@code true} while a run is in progress
   */
  boolean isRunning();

  /**
   * Close the session. Idempotent — safe to call multiple times; it never throws. No run starts
   * from now on: the run in progress is cancelled with the reason {@code "session closed"} and
   * waited for, then the session is torn down: {@link
   * com.standardapplied.helios.session.execution.ExecutionProvider#onSessionEnd}, for a session
   * with an execution provider, runs on a thread of its own and is waited for, and the event
   * streams end, without waiting for their readers. The future of the run in progress, and that of
   * a next run the close keeps from starting, are settled when it returns. The run's end and the
   * teardown share one deadline, {@link SessionLimits#stopGrace()} from the start of this call: a
   * step after one that used it up still runs but is not waited for, and a tool call or {@code
   * onSessionEnd} still running then is logged and left running. An interrupt of the caller ends
   * the waits early, its interrupt status kept. Called on a thread the session runs work on (a
   * tool, a hook), it cancels and returns at once, and the run's own thread finishes the teardown.
   */
  @Override
  void close();

  /**
   * Answer a pending {@code AskUserQuestion}. The run is blocked on a future keyed by {@code
   * questionId}; this call completes that future, the {@code AskUserQuestion} tool's executor wakes
   * up, and the run continues. A question belongs to the run that asked it: the run's end cancels
   * its unanswered questions, which then are unknown ids.
   *
   * @param questionId the {@code questionId} from the originating {@link QueryEvent.QuestionAsked
   *     QuestionAsked} event; non-blank
   * @param response the user's response; non-null. {@code response.questionId()} must equal {@code
   *     questionId}.
   * @throws NullPointerException if {@code questionId} or {@code response} is null
   * @throws IllegalArgumentException if {@code questionId} is blank or does not match an unanswered
   *     question, or if {@code response.questionId()} differs from {@code questionId}
   * @throws IllegalStateException if the session is closed
   */
  void answer(String questionId, AskUserQuestionResponse response);

  /**
   * The questions the session asked and that are not yet answered, cancelled or expired, in no
   * particular order: each is what its {@link QueryEvent.QuestionAsked QuestionAsked} event
   * carried, so a host that missed the event can still {@linkplain #answer answer} it. A question
   * id starting {@code perm-} asks to approve a tool call; one starting {@code q-} is the model's
   * own {@code AskUserQuestion}.
   *
   * @return an immutable snapshot; empty when no question is open
   */
  List<AskUserQuestionRequest> pendingQuestions();

  /**
   * Blocking convenience: {@code send(message).join()}. Open event streams, if any, receive the
   * run's events as usual.
   *
   * @param message the message; non-null
   * @return the terminal result of the run the message is part of
   * @throws NullPointerException if {@code message} is null
   */
  default ResultMessage runBlocking(UserMessage message) {
    return send(message).join();
  }

  /**
   * Typed blocking convenience: send the message, await the run's end, parse its final assistant
   * text as JSON against {@code schema}, return the typed result. Markdown fences and prose
   * prefixes around the JSON are tolerated.
   *
   * <ul>
   *   <li>A session built with an output schema (see {@code
   *       SessionOptions.Builder#withOutputSchema}) accepts only that schema here: a {@code schema}
   *       whose {@link OutputSchema#type() type} or {@link OutputSchema#schema() JSON schema}
   *       differs is refused before the message is sent. The loop has already self-corrected schema
   *       mismatches and {@link com.standardapplied.helios.core.common.SubmitValidator} rejections
   *       within {@code SessionLimits.maxTurns()}, so a returned value satisfies both; a model that
   *       never converges terminates as {@link ResultMessage.ErrorMaxTurns} and this method throws
   *       rather than returning a rejected value.
   *   <li>A session built without one parses the final answer against {@code schema} after the
   *       fact, with no structured-output handshake with the provider.
   * </ul>
   *
   * @param message the message; non-null
   * @param schema the output schema; non-null
   * @param <T> the parsed output type
   * @return the parsed final assistant message
   * @throws NullPointerException if {@code message} or {@code schema} is null
   * @throws IllegalArgumentException if the session has an output schema and {@code schema} differs
   *     from it; nothing is sent
   * @throws IllegalStateException if the run ended without a {@link ResultMessage.Success} — the
   *     caller cannot recover a typed value from {@link ResultMessage.Cancelled} / {@link
   *     ResultMessage.ErrorDuringExecution} / etc., or ended as a {@link ResultMessage.Success}
   *     whose result is blank, leaving nothing to parse
   * @throws com.standardapplied.helios.core.schema.StructuredOutputParseException if the final
   *     assistant text does not parse against the schema (carries a per-field diff) or, as {@link
   *     com.standardapplied.helios.core.schema.SubmitValidationException}, fails the schema's
   *     submit validator
   */
  <T> T runBlocking(UserMessage message, OutputSchema<T> schema);
}
