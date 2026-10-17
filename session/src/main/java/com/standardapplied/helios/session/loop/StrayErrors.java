/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The {@link Error}s a session's work threw where no thread waited for them any more, in the order
 * they were caught: one a callback on a tool call's own token threw, one a tool threw, or one that
 * reached a model turn after the turn was returned. The next model or tool call throws the first of
 * them, and the session settles its result with all of them, so a host {@code Error} that arrives
 * late still ends the session. Safe for concurrent use.
 */
public final class StrayErrors {

  private final List<Error> errors = new CopyOnWriteArrayList<>();

  void add(Error error) {
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
