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
 * A live, streamable, steerable agent session.
 *
 * <p>The session loop runs on a virtual thread that begins on the first {@link #send(UserMessage)}
 * call. Open {@link #events()} BEFORE the first {@code send} so the initial chunks reach the
 * reader: a stream opened while the session runs receives the events emitted from then on, and no
 * earlier event is replayed. One opened after the session emitted its terminal {@link
 * QueryEvent.LoopEnded} holds that same event once, then ends; if the session ended without a
 * {@code LoopEnded} (closed before it started, or the loop escaped with an {@code Error}) it ends
 * at once. No stream sees {@code LoopEnded} twice.
 *
 * <h2>Lifecycle</h2>
 *
 * <ol>
 *   <li>{@code AgentSession session = ... ;} construct via implementation factory.
 *   <li>{@code try (var events = session.events()) { ... }} open a reader, read on its own thread.
 *   <li>{@code session.send(...);} steer the session — first call starts the loop.
 *   <li>{@code session.interrupt(...);} optional mid-run steering.
 *   <li>{@code ResultMessage r = session.result().get();} await terminal.
 *   <li>{@code session.close();} release resources (idempotent; safe after natural termination).
 * </ol>
 *
 * <h2>Thread-safety</h2>
 *
 * Implementations must be safe for the standard "many producers, one loop" pattern: HTTP/UI threads
 * call {@code send} / {@code interrupt} / {@code close} concurrently; the agent loop thread reads
 * the queue and offers each event to every open {@link EventStream}; readers drain them on their
 * own threads.
 */
public interface AgentSession extends AutoCloseable {

  /**
   * Build a new session from the given options.
   *
   * @param options the composition record; non-null
   * @return a fresh, unstarted session
   * @throws NullPointerException if {@code options} is null
   */
  static AgentSession create(SessionOptions options) {
    Objects.requireNonNull(options, "options must not be null");
    return new AgentSessionImpl(options);
  }

  /**
   * Queue a user message for the agent loop to consume at the next iteration boundary. The first
   * call also starts the loop on a virtual thread.
   *
   * @param message the message; non-null
   * @throws NullPointerException if {@code message} is null
   * @throws IllegalStateException if the session is closed or terminal
   * @throws SteeringQueueFullException if the steering queue is full; the message is not queued
   */
  void send(UserMessage message);

  /**
   * Convenience for {@code send(UserMessage.text(text))}.
   *
   * @param text non-null, non-blank text
   */
  default void send(String text) {
    send(UserMessage.text(text));
  }

  /**
   * Steer the session mid-run by queueing a synthetic user message. The session continues; this is
   * not a session-ending action.
   *
   * @param reason a human-readable description; non-null, non-blank
   * @throws NullPointerException if {@code reason} is null
   * @throws IllegalArgumentException if {@code reason} is blank
   * @throws IllegalStateException if the session is closed or terminal
   * @throws SteeringQueueFullException if the steering queue is full; the interrupt is not queued
   */
  void interrupt(String reason);

  /**
   * Open a new stream of session events, attached before this returns. Open it BEFORE the first
   * {@link #send(UserMessage)} to observe every chunk, and read it on a thread of its own:
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
   * Future that completes when the session reaches a terminal {@link ResultMessage} and its runtime
   * has been torn down. Completes normally with the terminal value. It completes exceptionally only
   * with an {@link Error}, as itself: the first caught on a thread the session runs work on (the
   * loop, the model stream, a tool, a hook, a cancellation callback, the execution provider's start
   * or end). That is a host failure, not an agent-loop terminal: it ends the session, no further
   * model or tool call is made, and every later {@code Error}, and an exception of {@code
   * onSessionEnd}, rides along as {@linkplain Throwable#getSuppressed() suppressed}; one caught
   * after the future completed is logged. Nothing stops the JVM: the loop's virtual thread ends by
   * throwing the error to its uncaught-exception handler, and the host decides what an {@code
   * Error} means for the process.
   *
   * @return the terminal-result future
   */
  CompletableFuture<ResultMessage> result();

  /**
   * The stable session identifier.
   *
   * @return non-blank id
   */
  String sessionId();

  /**
   * The agent loop's current turn index. Reads the live state; subject to change between calls.
   *
   * @return the turn index (0-based; 0 if the loop has not yet started)
   */
  long currentTurnIndex();

  /**
   * Whether the agent loop has started: {@code true} from the first accepted {@link
   * #send(UserMessage)} or {@link #interrupt(String)} on, and once the session is closed or its
   * execution provider did not accept it, since such a session can never start. A host uses it to
   * tell a session nobody ever used from one at work.
   *
   * @return {@code true} once the loop started, the session closed or it ended at start-up
   */
  boolean isStarted();

  /**
   * Close the session. Idempotent — safe to call multiple times, and after the session ended; it
   * never throws. It cancels the session's token, whose callbacks run on a thread of their own, so
   * a callback that throws never reaches the caller.
   *
   * <p>Once the loop started, that is all this call does, and it returns at once: the loop stops
   * cooperatively and runs the teardown below on its own thread, and {@link #result()} completes
   * after that, which may be after this returns. For a session whose loop never started, this call
   * runs the teardown itself, on the caller's thread, and returns once {@link #result()} completed,
   * with {@link ResultMessage.Cancelled} unless a host {@link Error} failed it: the teardown below
   * can hold the caller up to {@link SessionLimits#stopGrace()}, and an interrupt of the caller
   * ends its waits early, its interrupt status kept. A session whose provider did not accept it at
   * start completed its result then, and this call only cancels its token.
   *
   * <p>The teardown runs on every end, a natural one included, and all its waits share one
   * deadline, {@link SessionLimits#stopGrace()} from its start: it cancels the session's token,
   * which cancels and interrupts every tool call still running, and waits until the deadline for
   * the token's callbacks and the tool calls to end; then it calls {@link
   * com.standardapplied.helios.session.execution.ExecutionProvider#onSessionEnd} on a thread of its
   * own and waits until the same deadline for it to return; then it ends the event streams, without
   * waiting for their readers, and the result completes. A step after one that used the deadline up
   * still runs but is not waited for. A callback, tool call or {@code onSessionEnd} still running
   * at the deadline is logged and left running: a tool that ignores both its token and interruption
   * may still run, and its effects land, after the result settles. An {@link Error} such a tool
   * throws then is logged; the result does not change.
   */
  @Override
  void close();

  /**
   * Answer a pending {@code AskUserQuestion}. The agent loop is blocked on a future keyed by {@code
   * questionId}; this call completes that future, the {@code AskUserQuestion} tool's executor wakes
   * up, and the session continues.
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
   * Blocking convenience: send the message, then await the terminal {@link ResultMessage}. Open
   * event streams, if any, receive its events as usual.
   *
   * @param message the message; non-null
   * @return the terminal result
   * @throws NullPointerException if {@code message} is null
   */
  default ResultMessage runBlocking(UserMessage message) {
    send(message);
    return result().join();
  }

  /**
   * Typed blocking convenience: send the message, await termination, parse the final assistant text
   * as JSON against {@code schema}, return the typed result. Markdown fences and prose prefixes
   * around the JSON are tolerated.
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
   * @throws IllegalStateException if the session terminated without a {@link ResultMessage.Success}
   *     — the caller cannot recover a typed value from {@link ResultMessage.Cancelled} / {@link
   *     ResultMessage.ErrorDuringExecution} / etc., or terminated as a {@link
   *     ResultMessage.Success} whose result is blank, leaving nothing to parse
   * @throws com.standardapplied.helios.core.schema.StructuredOutputParseException if the final
   *     assistant text does not parse against the schema (carries a per-field diff) or, as {@link
   *     com.standardapplied.helios.core.schema.SubmitValidationException}, fails the schema's
   *     submit validator
   */
  <T> T runBlocking(UserMessage message, OutputSchema<T> schema);
}
