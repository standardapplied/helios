/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import com.standardapplied.helios.session.store.InMemorySessionStore;
import com.standardapplied.helios.session.store.SessionEntry;
import com.standardapplied.helios.session.store.SessionStore;
import com.standardapplied.helios.session.store.StoredSession;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

/**
 * An in-memory store that records each batch it accepts and throws, for the append of each index,
 * what {@code failure} returns, if anything.
 */
class RecordingStore implements SessionStore {

  final InMemorySessionStore inner = new InMemorySessionStore();
  final List<List<SessionEntry>> batches = new CopyOnWriteArrayList<>();
  private final IntFunction<RuntimeException> failure;
  private final AtomicInteger appends = new AtomicInteger();

  RecordingStore() {
    this(index -> null);
  }

  RecordingStore(IntFunction<RuntimeException> failure) {
    this.failure = failure;
  }

  /** A store whose every load throws {@code thrown}. */
  static RecordingStore loadThrowing(RuntimeException thrown) {
    return new RecordingStore() {
      @Override
      public StoredSession load(String sessionId) {
        throw thrown;
      }
    };
  }

  @Override
  public StoredSession load(String sessionId) {
    return inner.load(sessionId);
  }

  @Override
  public void append(String sessionId, long expectedLastSeq, List<SessionEntry> entries) {
    var thrown = failure.apply(appends.getAndIncrement());
    if (thrown != null) {
      throw thrown;
    }
    inner.append(sessionId, expectedLastSeq, entries);
    batches.add(entries);
  }

  /**
   * Each accepted batch's entries: a message's role, {@code replaced}, or {@code ended <terminal>}.
   */
  List<List<String>> shapes() {
    return batches.stream()
        .map(batch -> batch.stream().map(RecordingStore::shape).toList())
        .toList();
  }

  private static String shape(SessionEntry entry) {
    return switch (entry) {
      case SessionEntry.MessageAdded added -> added.message().role().name();
      case SessionEntry.HistoryReplaced _ -> "replaced";
      case SessionEntry.RunEnded ended -> "ended " + ended.terminal();
    };
  }
}
