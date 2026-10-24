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
    return inOrder(bodies.stream().map(SseReplies::stream).toArray(StubHttpServer.Reply[]::new));
  }

  /**
   * Answers the n-th request with the n-th reply, and every request after the last reply with the
   * last reply.
   */
  public static Function<StubHttpServer.Request, StubHttpServer.Reply> inOrder(
      StubHttpServer.Reply... replies) {
    if (replies.length == 0) {
      throw new IllegalArgumentException("replies must not be empty");
    }
    var next = new AtomicInteger();
    return request -> replies[Math.min(next.getAndIncrement(), replies.length - 1)];
  }

  /** {@code 200} with {@code body} as an event stream. */
  public static StubHttpServer.Reply stream(String body) {
    return new StubHttpServer.Reply(200, Map.of("Content-Type", "text/event-stream"), body);
  }
}
