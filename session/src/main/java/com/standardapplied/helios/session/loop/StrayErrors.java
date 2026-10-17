/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The {@link Error}s a session's work threw where no thread waited for them any more, in the order
 * they were caught: one a callback on a tool call's own token threw, one a tool threw, one that
 * reached a model turn after the turn was returned, or one a compaction's summary call threw. The
 * next model or tool call throws the first of them, and the session settles its result with all of
 * them, so a host {@code Error} that arrives late still ends the session. Safe for concurrent use.
 */
public final class StrayErrors {

  private final List<Error> errors = new CopyOnWriteArrayList<>();

  /**
   * Keep {@code error}, before the work that threw it settles, so a waiter that gave up on that
   * work cannot lose it.
   *
   * @param error the error the work threw; non-null
   * @throws NullPointerException if {@code error} is null
   */
  public void add(Error error) {
    Objects.requireNonNull(error, "error must not be null");
    errors.add(error);
  }

  /**
   * @throws Error the first stray error, if one was caught
   */
  void throwFirst() {
    if (!errors.isEmpty()) {
      throw errors.getFirst();
    }
  }

  /**
   * The stray errors caught so far.
   *
   * @return the errors in the order they were caught, empty when none
   */
  public List<Error> all() {
    return List.copyOf(errors);
  }
}
