/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.persistence;

import com.standardapplied.helios.persistence.sql.SessionEntrySql;
import com.standardapplied.helios.session.store.SessionConflictException;
import com.standardapplied.helios.session.store.SessionEntry;
import com.standardapplied.helios.session.store.SessionEntryJson;
import com.standardapplied.helios.session.store.SessionStore;
import com.standardapplied.helios.session.store.SessionStoreException;
import com.standardapplied.helios.session.store.StoredSession;
import io.helidon.dbclient.DbClient;
import io.helidon.dbclient.DbRow;
import io.helidon.dbclient.DbTransaction;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * A {@link SessionStore} that keeps every session in one PostgreSQL table, {@code
 * helios_session_entries}, qualified with {@link PgConfig#schema()}: one row per entry, keyed by
 * {@code (session_id, seq)}, the entry held as {@link SessionEntryJson} text. The column is {@code
 * TEXT}, not {@code JSONB}, because {@code JSONB} refuses the {@code \u0000} escape, and a message
 * holding U+0000 must be stored verbatim for a provider to accept its replayed reasoning.
 *
 * <p>{@link #append} writes a batch in one transaction. Its first entry is inserted only when the
 * session's highest sequence number (0 for none) is the one the writer expects, and the primary key
 * refuses a second writer that read the same number; either refusal rolls the batch back and throws
 * {@link SessionConflictException} with the number the table holds. {@link #load} reads, in one
 * statement, the rows from the session's last {@code replaced} entry to its end.
 *
 * <p>The store applies no redactor, {@link PgConfig#redactor()} included: it holds the conversation
 * the model received, which the session redacted against its secret registry, and redacting it
 * again would change the messages a provider's reasoning is bound to.
 */
public final class PgSessionStore implements SessionStore {

  private static final String UNIQUE_VIOLATION = "23505";

  private final PgConfig config;
  private final DbClient dbClient;

  /**
   * @param config the database and schema; non-null
   * @throws NullPointerException if {@code config} is null
   */
  public PgSessionStore(PgConfig config) {
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.dbClient = config.dbClient();
  }

  /**
   * {@inheritDoc}
   *
   * @throws IllegalArgumentException if {@code sessionId} breaks {@link
   *     SessionStore#requireValidId}
   */
  @Override
  public StoredSession load(String sessionId) {
    var rows = rows(sessionId, SessionEntrySql.LOAD, sessionId, sessionId);
    var lastSeq = rows.isEmpty() ? 0 : rows.getLast().seq();
    return StoredSession.fold(sessionId, lastSeq, rows.stream().map(Row::entry).toList());
  }

  /**
   * {@inheritDoc}
   *
   * @throws IllegalArgumentException if {@code sessionId} breaks {@link
   *     SessionStore#requireValidId} or {@code entries} is empty
   */
  @Override
  public void append(String sessionId, long expectedLastSeq, List<SessionEntry> entries) {
    SessionStore.requireValidId(sessionId);
    var batch = List.copyOf(Objects.requireNonNull(entries, "entries must not be null"));
    if (batch.isEmpty()) {
      throw new IllegalArgumentException("an append needs at least one entry");
    }
    if (!inserted(sessionId, expectedLastSeq, batch)) {
      throw new SessionConflictException(sessionId, expectedLastSeq, lastSeq(sessionId));
    }
  }

  /**
   * The session's whole log, in order.
   *
   * @param sessionId the session id; valid per {@link SessionStore#requireValidId}
   * @return the log; empty for a session never written
   * @throws SessionStoreException if the table cannot be read or holds a row that is no entry
   */
  public List<SessionEntry> entries(String sessionId) {
    return rows(sessionId, SessionEntrySql.ENTRIES, sessionId).stream().map(Row::entry).toList();
  }

  /**
   * Remove a session's rows. The deletion locks the table against writers, so it waits for appends
   * in progress and appends wait for it: an append is removed whole, or conflicts afterwards.
   *
   * @param sessionId the session id; valid per {@link SessionStore#requireValidId}
   * @return whether the table held any
   * @throws SessionStoreException if the rows cannot be deleted
   */
  public boolean delete(String sessionId) {
    SessionStore.requireValidId(sessionId);
    return inStore(
        "delete",
        sessionId,
        () -> {
          var tx = dbClient.transaction();
          try {
            tx.dml(config.qualify(SessionEntrySql.LOCK_WRITERS));
            var deleted = tx.dml(config.qualify(SessionEntrySql.DELETE), sessionId) > 0;
            tx.commit();
            return deleted;
          } catch (RuntimeException e) {
            rollback(tx, e);
            throw e;
          }
        });
  }

  /** Whether the batch was written; {@code false} when the table refused it as a conflict. */
  private boolean inserted(String sessionId, long expectedLastSeq, List<SessionEntry> batch) {
    var tx = dbClient.transaction();
    try {
      var first = new Row(expectedLastSeq + 1, batch.getFirst());
      if (tx.dml(
              config.qualify(SessionEntrySql.INSERT_FIRST),
              first.columns(sessionId, sessionId, expectedLastSeq))
          == 0) {
        tx.rollback();
        return false;
      }
      for (var i = 1; i < batch.size(); i++) {
        var row = new Row(expectedLastSeq + 1 + i, batch.get(i));
        tx.dml(config.qualify(SessionEntrySql.INSERT), row.columns(sessionId));
      }
      tx.commit();
      return true;
    } catch (RuntimeException e) {
      rollback(tx, e);
      if (violatesUniqueKey(e)) {
        return false;
      }
      throw new SessionStoreException("cannot append to session " + sessionId, e);
    }
  }

  private static void rollback(DbTransaction tx, RuntimeException failure) {
    try {
      tx.rollback();
    } catch (RuntimeException e) {
      failure.addSuppressed(e);
    }
  }

  private static boolean violatesUniqueKey(Throwable failure) {
    for (var cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
        return true;
      }
    }
    return false;
  }

  private List<Row> rows(String sessionId, String sql, Object... parameters) {
    SessionStore.requireValidId(sessionId);
    return inStore(
        "read",
        sessionId,
        () -> dbClient.execute().query(config.qualify(sql), parameters).map(Row::read).toList());
  }

  private long lastSeq(String sessionId) {
    return inStore(
        "read",
        sessionId,
        () ->
            dbClient
                .execute()
                .get(config.qualify(SessionEntrySql.LAST_SEQ), sessionId)
                .orElseThrow()
                .column("last_seq")
                .getLong());
  }

  private static <T> T inStore(String action, String sessionId, Supplier<T> work) {
    try {
      return work.get();
    } catch (RuntimeException e) {
      throw new SessionStoreException("cannot " + action + " session " + sessionId, e);
    }
  }

  /** One row of the table: an entry at its sequence number. */
  private record Row(long seq, SessionEntry entry) {

    static Row read(DbRow row) {
      return new Row(
          row.column("seq").getLong(), SessionEntryJson.read(row.column("entry").getString()));
    }

    /** The row's columns in insert order, followed by {@code condition}. */
    Object[] columns(String sessionId, Object... condition) {
      var columns =
          Stream.of(
              sessionId,
              seq,
              type(entry),
              entry.runId(),
              SessionEntryJson.write(entry),
              entry.at().atOffset(ZoneOffset.UTC));
      return Stream.concat(columns, Stream.of(condition)).toArray();
    }

    private static String type(SessionEntry entry) {
      return switch (entry) {
        case SessionEntry.MessageAdded _ -> "message";
        case SessionEntry.HistoryReplaced _ -> "replaced";
      };
    }
  }
}
