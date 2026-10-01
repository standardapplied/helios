/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Bounded per-session inbox for user messages produced mid-run by {@code AgentSession.send(...)}.
 *
 * <p>The agent loop drains this queue at iteration boundary — after a turn ends, before the next
 * one begins. The drained batch is concatenated into a single composite {@link UserMessage} by the
 * loop. This is the only steering-input mechanism into a running session; tools, model output, and
 * cancellation flow through other channels.
 *
 * <p>The queue is bounded by {@code capacity}; {@link #offer(UserMessage)} returns {@code false}
 * immediately when full so the caller can surface backpressure (typically HTTP 429). Drops are
 * never silent.
 *
 * <h2>Thread-safety</h2>
 *
 * Thread-safe. The underlying {@link LinkedBlockingQueue} synchronises {@code offer}, {@code
 * drainTo}, and {@code size} internally. Multiple producers (HTTP request threads) and one consumer
 * (the agent loop) are the expected usage pattern; multi-consumer is supported but produces
 * disjoint drained batches.
 */
public final class SteeringQueue {

  private final LinkedBlockingQueue<UserMessage> queue;
  private final int capacity;

  /**
   * Create a steering queue with the given capacity.
   *
   * @param capacity maximum number of pending messages; must be positive
   * @throws IllegalArgumentException if {@code capacity} is not strictly positive
   */
  public SteeringQueue(int capacity) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be positive, got " + capacity);
    }
    this.capacity = capacity;
    this.queue = new LinkedBlockingQueue<>(capacity);
  }

  /**
   * Capacity supplied at construction.
   *
   * @return the queue's maximum size
   */
  public int capacity() {
    return capacity;
  }

  /**
   * Non-blocking enqueue. Returns immediately whether or not the queue accepted the message.
   *
   * @param message the user message; non-null
   * @return {@code true} if accepted; {@code false} if the queue was already at {@code capacity}
   * @throws NullPointerException if {@code message} is null
   */
  public boolean offer(UserMessage message) {
    Objects.requireNonNull(message, "message must not be null");
    return queue.offer(message);
  }

  /**
   * Atomically drain every pending message into a fresh list. The returned list reflects the
   * drained snapshot; messages enqueued after {@code drain()} returns are not included.
   *
   * @return a new list of drained messages in FIFO order; empty if nothing was queued
   */
  public List<UserMessage> drain() {
    var batch = new ArrayList<UserMessage>();
    queue.drainTo(batch);
    return batch;
  }

  /**
   * Current number of pending messages. Useful for observability; do not use for control flow —
   * {@code size} can change between the call and any subsequent action.
   *
   * @return the current size
   */
  public int size() {
    return queue.size();
  }
}
