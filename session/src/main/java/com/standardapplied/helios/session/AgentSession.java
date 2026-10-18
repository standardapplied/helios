/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session;

import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.schema.RawOutputCapturePolicy;
import com.standardapplied.helios.core.schema.StructuredContentParser;
import com.standardapplied.helios.session.ask.AskUserQuestionResponse;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/**
 * A live, streamable, steerable agent session.
 *
 * <p>The session loop runs on a virtual thread that begins on the first {@link #send(UserMessage)}
 * call. Subscribe to {@link #events()} BEFORE the first {@code send} so the initial chunks reach
 * the subscriber: one that attaches while the session runs receives the events emitted from then
 * on, and no earlier event is replayed. One that attaches after the session emitted its terminal
 * {@link QueryEvent.LoopEnded} receives that same event once it requests, then {@code onComplete};
 * if the session ended without a {@code LoopEnded} (closed before it started, or the loop escaped
 * with an {@code Error}) it is completed at once. {@code events().subscribe(...)} never throws for
 * a non-null subscriber, and no subscriber sees {@code LoopEnded} twice.
 *
 * <h2>Lifecycle</h2>
 *
 * <ol>
 *   <li>{@code AgentSession session = ... ;} construct via implementation factory.
 *   <li>{@code session.events().subscribe(subscriber);} attach observers.
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
 * the queue and writes to the publisher; subscribers observe via {@code events()}.
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
   * Stream of session events. Subscribe BEFORE the first {@link #send(UserMessage)} to observe
   * every chunk. Implementations buffer per-subscriber so a slow subscriber back-pressures the
   * agent loop rather than dropping events.
   *
   * @return a single-publisher to which any number of subscribers may attach
   */
  Flow.Publisher<QueryEvent> events();

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
   * with {@link ResultMessage.Cancelled} unless a host {@link Error} failed it: each of the
   * teardown's waits below can hold the caller up to {@link SessionLimits#teardownGrace()}, and an
   * interrupt of the caller ends those waits early, its interrupt status kept. A session whose
   * provider did not accept it at start completed its result then, and this call only cancels its
   * token.
   *
   * <p>The teardown runs on every end, a natural one included: it cancels the session's token,
   * which cancels and interrupts every tool call still running, and waits {@link
   * SessionLimits#teardownGrace()} for the token's callbacks and then up to that grace for the tool
   * calls to end; then it calls {@link
   * com.standardapplied.helios.session.execution.ExecutionProvider#onSessionEnd} on a thread of its
   * own and waits the same grace for it to return; then it shuts the session's scheduler down and
   * gives event subscribers the same grace to drain, and the result completes. A callback, tool
   * call or {@code onSessionEnd} still running when its grace has passed is logged and left
   * running: a tool that ignores both its token and interruption may still run, and its effects
   * land, after the result settles. An {@link Error} such a tool throws then is logged; the result
   * does not change.
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
   * Blocking convenience: send the message, then await the terminal {@link ResultMessage}.
   * Subscribers — if any — observe the stream in the usual way.
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
   * as JSON against {@code schema}, return the typed result.
   *
   * <p>The session is driven exactly as {@link #runBlocking(UserMessage)} — there is no structured-
   * output handshake with the provider, which keeps the streaming/steering shape intact. Models are
   * expected to honor the schema's shape in their final answer; markdown fences and prose prefixes
   * are tolerated via {@link StructuredContentParser}'s existing recovery passes.
   *
   * <p>When the session was built with the same {@code schema} (see {@code
   * SessionOptions.Builder#withOutputSchema}), the loop has already self-corrected schema
   * mismatches and {@link com.standardapplied.helios.core.common.SubmitValidator} rejections within
   * {@code SessionLimits.maxTurns()}, so a returned value always satisfies both; a model that never
   * converges terminates as {@link ResultMessage.ErrorMaxTurns} and this method throws rather than
   * returning a rejected value.
   *
   * @param message the message; non-null
   * @param schema the output schema; non-null
   * @param <T> the parsed output type
   * @return the parsed final assistant message
   * @throws NullPointerException if {@code message} or {@code schema} is null
   * @throws IllegalStateException if the session terminated without a {@link ResultMessage.Success}
   *     — the caller cannot recover a typed value from {@link ResultMessage.Cancelled} / {@link
   *     ResultMessage.ErrorDuringExecution} / etc., or terminated as a {@link
   *     ResultMessage.Success} whose result is blank, leaving nothing to parse
   * @throws com.standardapplied.helios.core.schema.StructuredOutputParseException if the final
   *     assistant text does not parse against the schema (carries a per-field diff) or, as {@link
   *     com.standardapplied.helios.core.schema.SubmitValidationException}, fails the schema's
   *     submit validator
   */
  default <T> T runBlocking(UserMessage message, OutputSchema<T> schema) {
    Objects.requireNonNull(message, "message must not be null");
    Objects.requireNonNull(schema, "schema must not be null");
    var terminal = runBlocking(message);
    if (!(terminal instanceof ResultMessage.Success success)) {
      throw new IllegalStateException(
          "session terminated as "
              + terminal.getClass().getSimpleName()
              + "; cannot parse a typed result from a non-Success terminal");
    }
    if (Strings.isBlank(success.result())) {
      throw new IllegalStateException(
          "session ended with Success but produced no result to parse as "
              + schema.type().getSimpleName());
    }
    return StructuredContentParser.parse(
        success.result(), schema, SessionJson.STRUCTURED, rawOutputCapturePolicy());
  }

  /** Structured-output retention policy inherited from the session model. */
  default RawOutputCapturePolicy rawOutputCapturePolicy() {
    return RawOutputCapturePolicy.ENABLED;
  }
}
