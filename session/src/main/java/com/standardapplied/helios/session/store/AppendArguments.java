/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.store;

import java.util.List;
import java.util.Objects;

/** The arguments every store checks before it appends. */
final class AppendArguments {

  private AppendArguments() {}

  /**
   * Check an append's arguments.
   *
   * @return a copy of {@code entries}
   * @throws NullPointerException if an argument is null or {@code entries} holds a null
   * @throws IllegalArgumentException if {@code sessionId} is invalid or {@code entries} empty
   */
  static List<SessionEntry> batch(String sessionId, List<SessionEntry> entries) {
    SessionStore.requireValidId(sessionId);
    var batch = List.copyOf(Objects.requireNonNull(entries, "entries must not be null"));
    if (batch.isEmpty()) {
      throw new IllegalArgumentException("an append needs at least one entry");
    }
    return batch;
  }
}
