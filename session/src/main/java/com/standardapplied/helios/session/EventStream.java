/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

/**
 * One reader's stream of a session's {@link QueryEvent}s, read once with for-each inside
 * try-with-resources, the shape of {@link java.nio.file.DirectoryStream}:
 *
 * <pre>{@code
 * try (var events = session.events()) {
 *   for (var event : events) {
 *     ...
 *   }
 * }
 * }</pre>
 *
 * <p>{@link AgentSession#events()} opens a new stream per call, attached before it returns: the
 * stream receives every event the session emits after that, across every run, until the session is
 * closed; a stream opened after the session closed ends at once. A run's result is the future
 * {@link AgentSession#send} returned for it, never a replayed event.
 *
 * <p>{@link #iterator()} may be called once; a second call throws {@link IllegalStateException}.
 * The iterator's {@code hasNext()} blocks until an event is buffered or the stream has ended, and
 * {@code next()} past the end throws {@link java.util.NoSuchElementException}. The stream ends when
 * it is closed, when the session ended and every buffered event has been read, or when the reading
 * thread is interrupted, in which case {@code hasNext()} returns {@code false} with the interrupt
 * status kept.
 *
 * <p>A session's stream holds up to 256 events. A routine event never waits for a reader: a stream
 * whose buffer is full misses it. {@link QueryEvent.RunEnded}, {@link QueryEvent.QuestionAsked} and
 * {@link QueryEvent.Error} wait up to 30 s for room, then are dropped from that stream with a
 * WARNING; a run's terminal stays readable through the future {@link AgentSession#send} returned
 * and an open question through {@link AgentSession#pendingQuestions()}. Every emitted event is in
 * each open stream's buffer, or missed by it, before the run's future completes; the session never
 * waits for a reader to read it, so a reader that must have handled an event before the future
 * completes synchronises with its own reading thread.
 */
public interface EventStream extends Iterable<QueryEvent>, AutoCloseable {

  /**
   * Detach the stream from its session at once and end its iteration, discarding what it buffered.
   * Idempotent, never throws, and callable from any thread, so another thread can end a blocked
   * read.
   */
  @Override
  void close();
}
