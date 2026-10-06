/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** A {@link StubHttpServer} handler that streams recorded server-sent-event bodies. */
public final class SseReplies {

  private SseReplies() {}

  /**
   * Answers the n-th request with {@code 200} and the n-th body, and every request after the last
   * body with the last body.
   */
  public static Function<StubHttpServer.Request, StubHttpServer.Reply> inOrder(
      List<String> bodies) {
    if (bodies.isEmpty()) {
      throw new IllegalArgumentException("bodies must not be empty");
    }
    var next = new AtomicInteger();
    return request ->
        new StubHttpServer.Reply(
            200,
            Map.of("Content-Type", "text/event-stream"),
            bodies.get(Math.min(next.getAndIncrement(), bodies.size() - 1)));
  }
}
