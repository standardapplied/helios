/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

/**
 * A message or interrupt refused because the session's {@link SteeringQueue} is full. Nothing was
 * queued; the caller may retry once the agent loop has drained the queue. A host tells it apart
 * from the other {@link IllegalStateException}s of {@link AgentSession#send(UserMessage)} to answer
 * backpressure (HTTP {@code 429}) rather than a session that can take no more messages.
 */
public final class SteeringQueueFullException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  /**
   * Create the exception.
   *
   * @param message what was refused and the queue's capacity
   */
  public SteeringQueueFullException(String message) {
    super(message);
  }
}
