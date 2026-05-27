/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.schema.StructuredContentParser;
import ai.singlr.session.ask.AskUserQuestionResponse;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import tools.jackson.databind.json.JsonMapper;

/**
 * A live, streamable, steerable agent session.
 *
 * <p>The session loop runs on a virtual thread that begins on the first {@link #send(UserMessage)}
 * call. Subscribe to {@link #events()} BEFORE the first {@code send} so the initial chunks reach
 * the subscriber — late subscribers attach to a publisher that has already advanced.
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
   * @throws IllegalStateException if the session is already closed, or if the steering queue is
   *     full
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
   * @throws IllegalStateException if the session is closed
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
   * Future that completes when the session reaches a terminal {@link ResultMessage}. Completes
   * normally with the terminal value; exceptional completion indicates a session bug, not an
   * agent-loop terminal (those are returned via the value, not the exception).
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
   * Release session resources. Cancels the agent loop, closes the event publisher, and releases the
   * underlying virtual thread. Idempotent — safe to call multiple times, and safe to call after
   * natural termination.
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
   * @param message the message; non-null
   * @param schema the output schema; non-null
   * @param <T> the parsed output type
   * @return the parsed final assistant message
   * @throws NullPointerException if {@code message} or {@code schema} is null
   * @throws IllegalStateException if the session terminated without a {@link ResultMessage.Success}
   *     — the caller cannot recover a typed value from {@link ResultMessage.Cancelled} / {@link
   *     ResultMessage.ErrorDuringExecution} / etc.
   * @throws ai.singlr.core.schema.StructuredOutputParseException if the final assistant text does
   *     not parse against the schema; carries a per-field diff
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
    return StructuredContentParser.parse(success.result(), schema, JacksonJsonAdapter.SHARED);
  }

  /**
   * Jackson 3.x adapter for {@link StructuredContentParser}. Held as a constant so we don't
   * allocate a fresh {@code JsonMapper} per typed {@code runBlocking} call.
   */
  final class JacksonJsonAdapter implements StructuredContentParser.JsonAdapter {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    static final JacksonJsonAdapter SHARED = new JacksonJsonAdapter();

    private JacksonJsonAdapter() {}

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> toMap(String json) {
      return MAPPER.readValue(json, Map.class);
    }

    @Override
    public <T> T fromMap(Map<String, Object> map, Class<T> type) {
      return MAPPER.convertValue(map, type);
    }
  }
}
