/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.common.Redactor;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Role;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SerializedError;
import com.standardapplied.helios.session.store.SessionConflictException;
import com.standardapplied.helios.session.store.SessionEntry;
import com.standardapplied.helios.session.store.SessionStore;
import com.standardapplied.helios.session.store.SessionStoreException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;

/**
 * A session's history as its {@link SessionStore} holds it. {@link #open} loads the stored
 * conversation after the system prompt; {@link #write} appends what the history gained since the
 * last write, as one batch: one {@link SessionEntry.HistoryReplaced} with the whole history when it
 * was replaced since, otherwise one {@link SessionEntry.MessageAdded} per message added, leading
 * {@code SYSTEM} messages never included, and nothing when nothing is new. Each append names the
 * last sequence number read or written. Once the store refuses a write, nothing more is written and
 * {@link #refused()} holds, so the session closes itself: its memory and its store no longer agree.
 *
 * <h2>Thread-safety</h2>
 *
 * Written by the thread of the run in progress, one run at a time; {@link #refused()} may be read
 * by any thread.
 */
public final class StoredHistory {

  private final Optional<SessionStore> store;
  private long lastSeq;
  private int stored;
  private long replacements;
  private volatile boolean refused;

  private StoredHistory(Optional<SessionStore> store) {
    this.store = store;
  }

  /**
   * The history of a session without a store, which writes nothing.
   *
   * @return a history that is never stored
   */
  public static StoredHistory none() {
    return new StoredHistory(Optional.empty());
  }

  /**
   * Load the session's stored conversation into its history, after what the history already holds
   * (the system prompt), and keep the store's last sequence number.
   *
   * @param store the session's store; non-null
   * @param session the session's own state; non-null
   * @return the stored history
   * @throws SessionStoreException if the store cannot load the session
   */
  public static StoredHistory open(SessionStore store, SessionState session) {
    var history = new StoredHistory(Optional.of(store));
    var loaded = store.load(session.sessionId());
    loaded.history().forEach(session.history()::append);
    history.lastSeq = loaded.lastSeq();
    history.stored = session.history().snapshot().size();
    history.replacements = session.history().replacements();
    return history;
  }

  /**
   * Whether the store refused a write, after which the session takes no message.
   *
   * @return {@code true} once an append failed
   */
  public boolean refused() {
    return refused;
  }

  /**
   * Append what {@code state}'s history gained since the last write, each entry carrying the run's
   * id and {@code at}. A refused write is the run's final terminal: {@code ErrorDuringExecution} of
   * kind {@code SessionConflict} for a conflict, {@code SessionStoreFailed} otherwise, its message
   * redacted.
   *
   * @return the refusal; empty when the write succeeded or there was nothing to write
   * @throws Error an {@link Error} the store threw, as itself or as a joined future's cause: a host
   *     failure, never a refusal
   */
  Optional<ResultMessage> write(SessionState state, Instant at, Redactor redactor) {
    if (store.isEmpty() || refused) {
      return Optional.empty();
    }
    var history = state.history();
    var replaced = history.replacements();
    var snapshot = history.snapshot();
    var entries = entries(snapshot, replaced != replacements, state.runId(), at);
    if (entries.isEmpty()) {
      return Optional.empty();
    }
    try {
      append(store.get(), state.sessionId(), entries);
    } catch (SessionStoreException e) {
      var kind = e instanceof SessionConflictException ? "SessionConflict" : "SessionStoreFailed";
      var refusal =
          StopClassifier.errorDuringExecution(
              state, SerializedError.of(kind, e.getMessage()).redactedBy(redactor));
      state.setFinalTerminal(refusal);
      return Optional.of(refusal);
    }
    lastSeq += entries.size();
    stored = snapshot.size();
    replacements = replaced;
    return Optional.empty();
  }

  private void append(SessionStore store, String sessionId, List<SessionEntry> entries) {
    try {
      store.append(sessionId, lastSeq, entries);
    } catch (SessionStoreException e) {
      refused = true;
      throw e;
    } catch (RuntimeException e) {
      if (e instanceof CompletionException && e.getCause() instanceof Error error) {
        throw error;
      }
      refused = true;
      throw new SessionStoreException(
          store.getClass().getName() + " failed to append to session " + sessionId, e);
    }
  }

  private List<SessionEntry> entries(
      List<Message> snapshot, boolean replaced, String runId, Instant at) {
    var system = 0;
    while (system < snapshot.size() && snapshot.get(system).role() == Role.SYSTEM) {
      system++;
    }
    if (replaced) {
      return List.of(
          new SessionEntry.HistoryReplaced(runId, at, snapshot.subList(system, snapshot.size())));
    }
    return snapshot.subList(stored, snapshot.size()).stream()
        .<SessionEntry>map(message -> new SessionEntry.MessageAdded(runId, at, message))
        .toList();
  }
}
