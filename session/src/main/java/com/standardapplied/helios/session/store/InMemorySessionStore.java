/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.store;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link SessionStore} that holds every session's log in this process's memory, for tests and for
 * sessions that need to outlive a handle but not the process. {@link #append} checks and writes
 * under one lock per store.
 */
public final class InMemorySessionStore implements SessionStore {

  private final Map<String, List<SessionEntry>> logs = new HashMap<>();

  @Override
  public StoredSession load(String sessionId) {
    var log = entries(sessionId);
    return StoredSession.fold(sessionId, log.size(), log);
  }

  @Override
  public void append(String sessionId, long expectedLastSeq, List<SessionEntry> entries) {
    var batch = AppendArguments.batch(sessionId, entries);
    synchronized (logs) {
      var lastSeq = logs.getOrDefault(sessionId, List.of()).size();
      if (lastSeq != expectedLastSeq) {
        throw new SessionConflictException(sessionId, expectedLastSeq, lastSeq);
      }
      logs.computeIfAbsent(sessionId, id -> new ArrayList<>()).addAll(batch);
    }
  }

  /**
   * The session's whole log, in order.
   *
   * @param sessionId the session id; valid per {@link SessionStore#requireValidId}
   * @return a copy of the log; empty for a session never written
   */
  public List<SessionEntry> entries(String sessionId) {
    SessionStore.requireValidId(sessionId);
    synchronized (logs) {
      return List.copyOf(logs.getOrDefault(sessionId, List.of()));
    }
  }

  /**
   * Remove a session.
   *
   * @param sessionId the session id; valid per {@link SessionStore#requireValidId}
   * @return whether the store held it
   */
  public boolean delete(String sessionId) {
    SessionStore.requireValidId(sessionId);
    synchronized (logs) {
      return logs.remove(sessionId) != null;
    }
  }
}
