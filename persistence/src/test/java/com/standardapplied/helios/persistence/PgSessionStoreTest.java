/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.persistence.sql.SessionEntrySql;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.session.store.SessionConflictException;
import com.standardapplied.helios.session.store.SessionEntry;
import com.standardapplied.helios.session.store.SessionEntryJson;
import com.standardapplied.helios.session.store.SessionStore;
import com.standardapplied.helios.session.store.SessionStoreException;
import com.standardapplied.helios.session.store.StoredSession;
import com.standardapplied.helios.session.test.SessionStoreContract;
import com.standardapplied.helios.testing.ScriptedModel;
import java.io.IOException;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class PgSessionStoreTest extends SessionStoreContract {

  private static final String LOCK_FIRST_ROW =
      "SELECT seq FROM %s.helios_session_entries WHERE session_id = ? AND seq = 1 FOR UPDATE";

  @BeforeEach
  void setUp() {
    PgTestSupport.truncateSessions();
  }

  @Override
  protected SessionStore store() {
    return new PgSessionStore(PgTestSupport.pgConfig());
  }

  @Test
  void aNulInAToolCallAndAToolResultRoundTripsVerbatim() {
    var store = store();
    var history =
        List.of(
            Message.assistant(
                "calling",
                List.of(new ToolCall("call-1", "echo", Map.of("v", "\u0000"))),
                Map.of()),
            Message.tool("call-1", "echo", "a\u0000b"));
    store.append(SID, 0, history.stream().map(SessionStoreContract::added).toList());

    assertEquals(new StoredSession(SID, 2, history), store.load(SID));
  }

  @Test
  void aStaleAndAnAheadWriterBothConflictAndWriteNothing() {
    var store = store();
    store.append(SID, 0, messages(3));

    var stale =
        assertThrows(SessionConflictException.class, () -> store.append(SID, 2, messages(1)));
    var ahead =
        assertThrows(SessionConflictException.class, () -> store.append(SID, 4, messages(1)));

    assertTrue(stale.getMessage().endsWith("expected last sequence number 2, the store holds 3"));
    assertTrue(ahead.getMessage().endsWith("expected last sequence number 4, the store holds 3"));
    assertEquals(3, rowCount());
  }

  @Test
  void loadReadsFromTheLastReplacementThroughThePartialIndex() {
    var store = store();
    store.append(SID, 0, messages(500));
    store.append(SID, 500, List.of(replaced(Message.user("summary"))));
    store.append(SID, 501, List.of(added(Message.user("x")), added(Message.assistant("y"))));
    PgTestSupport.dbClient().execute().dml("ANALYZE helios_session_entries");

    var plan =
        PgTestSupport.dbClient()
            .execute()
            .query("EXPLAIN " + PgTestSupport.pgConfig().qualify(SessionEntrySql.LOAD), SID, SID)
            .map(row -> row.column("QUERY PLAN").getString())
            .collect(Collectors.joining("\n"));

    assertEquals(
        new StoredSession(
            SID, 503, List.of(Message.user("summary"), Message.user("x"), Message.assistant("y"))),
        store.load(SID));
    assertTrue(plan.contains("idx_helios_session_entries_replaced"), plan);
  }

  @Test
  void entriesAreTheWholeLogAndDeleteRemovesIt() {
    var store = new PgSessionStore(PgTestSupport.pgConfig());
    var log =
        List.of(added(Message.user("a")), replaced(Message.user("s")), added(Message.user("b")));
    store.append(SID, 0, log);
    store.append("chat-2", 0, messages(1));

    assertEquals(log, store.entries(SID));
    assertTrue(store.delete(SID));
    assertFalse(store.delete(SID));
    assertEquals(List.of(), store.entries(SID));
    assertEquals(1, store.load("chat-2").lastSeq());
  }

  @Test
  void theAdministrativeMethodsRejectAnIdOutsideTheRule() {
    var store = new PgSessionStore(PgTestSupport.pgConfig());

    assertThrows(IllegalArgumentException.class, () -> store.entries("../escape"));
    assertThrows(IllegalArgumentException.class, () -> store.delete("../escape"));
  }

  @Test
  void aNullConfigIsRejected() {
    var thrown = assertThrows(NullPointerException.class, () -> new PgSessionStore(null));

    assertEquals("config must not be null", thrown.getMessage());
  }

  @Test
  void aSecondWriterThatReadTheSameSequenceNumberIsRefusedByThePrimaryKey() {
    var store = new PgSessionStore(PgTestSupport.pgConfig());
    var holder = PgTestSupport.dbClient().transaction();
    holder.dml(PgTestSupport.pgConfig().qualify(SessionEntrySql.INSERT), row(1));
    var append = appendInBackground(store, 0, 1);
    awaitLockWaiters(1, append);

    holder.commit();

    var conflict = Await.failure("the blocked append", append);
    assertEquals(SessionConflictException.class, conflict.getClass());
    assertTrue(
        conflict.getMessage().endsWith("expected last sequence number 0, the store holds 1"));
    assertEquals(1, rowCount());
  }

  @Test
  void aDeletionWaitsForAnAppendInProgressAndRemovesItsRowsToo() {
    var store = new PgSessionStore(PgTestSupport.pgConfig());
    store.append(SID, 0, messages(3));
    var holder = PgTestSupport.dbClient().transaction();
    holder.dml(PgTestSupport.pgConfig().qualify(SessionEntrySql.INSERT), row(5));
    var append = appendInBackground(store, 3, 2);
    awaitLockWaiters(1, append);
    var delete = deleteInBackground(store);
    awaitLockWaiters(2, delete);

    holder.rollback();

    Await.value("the append", append);
    assertTrue(Await.value("the deletion", delete));
    assertEquals(0, rowCount());
  }

  @Test
  void anAppendWaitsForADeletionInProgressAndThenConflicts() {
    var store = new PgSessionStore(PgTestSupport.pgConfig());
    store.append(SID, 0, messages(3));
    var holder = PgTestSupport.dbClient().transaction();
    holder.get(PgTestSupport.pgConfig().qualify(LOCK_FIRST_ROW), SID);
    var delete = deleteInBackground(store);
    awaitLockWaiters(1, delete);
    var append = appendInBackground(store, 3, 1);
    awaitLockWaiters(2, append);

    holder.commit();

    assertTrue(Await.value("the deletion", delete));
    var conflict = Await.failure("the append", append);
    assertEquals(SessionConflictException.class, conflict.getClass());
    assertTrue(
        conflict.getMessage().endsWith("expected last sequence number 3, the store holds 0"));
    assertEquals(0, rowCount());
  }

  @Test
  void aDatabaseFailureIsAStoreFailureNamingTheSession() {
    var store =
        new PgSessionStore(
            PgConfig.newBuilder()
                .withDbClient(PgTestSupport.dbClient())
                .withSchema("absent")
                .build());

    var load = assertThrows(SessionStoreException.class, () -> store.load(SID));
    var entries = assertThrows(SessionStoreException.class, () -> store.entries(SID));
    var append = assertThrows(SessionStoreException.class, () -> store.append(SID, 0, messages(1)));
    var delete = assertThrows(SessionStoreException.class, () -> store.delete(SID));

    assertEquals("cannot read session " + SID, load.getMessage());
    assertEquals("cannot read session " + SID, entries.getMessage());
    assertEquals(SessionStoreException.class, append.getClass());
    assertEquals("cannot append to session " + SID, append.getMessage());
    assertNotNull(append.getCause());
    assertEquals("cannot delete session " + SID, delete.getMessage());
  }

  @Test
  void aSessionReopenedOnANewStoreSendsTheFirstRunsConversation() {
    var first = ScriptedModel.newBuilder().withTextTurn("one").build();
    try (var session = AgentSession.open(options(first))) {
      Await.value("run 1", session.send("first"));
    }
    var second = ScriptedModel.newBuilder().withTextTurn("two").build();
    try (var session = AgentSession.open(options(second))) {
      Await.value("run 2", session.send("second"));
    }

    var stored = new PgSessionStore(PgTestSupport.pgConfig()).entries(SID);
    var firstRun =
        stored.subList(0, 2).stream()
            .map(entry -> ((SessionEntry.MessageAdded) entry).message())
            .toList();
    assertEquals(List.of(Message.user("first")), first.calls().getFirst());
    assertEquals("one", firstRun.getLast().content());
    assertEquals(
        List.of(firstRun.getFirst(), firstRun.getLast(), Message.user("second")),
        second.calls().getFirst());
  }

  @Test
  void theSchemaCreatesTheEntriesTableAndNoneOfTheV1ConversationTables() throws IOException {
    var tx = PgTestSupport.dbClient().transaction();
    try {
      tx.dml("CREATE SCHEMA fresh");
      tx.dml("SET LOCAL search_path TO fresh");
      for (var statement : PgTestSupport.schemaStatements()) {
        tx.dml(statement);
      }
      var tables =
          tx.query("SELECT table_name FROM information_schema.tables WHERE table_schema = 'fresh'")
              .map(row -> row.column("table_name").getString())
              .collect(Collectors.toSet());

      assertEquals(
          Set.of(
              "helios_prompts",
              "helios_traces",
              "helios_spans",
              "helios_annotations",
              "helios_session_entries",
              "helios_agent_runs",
              "helios_tool_calls"),
          tables);
    } finally {
      tx.rollback();
    }
  }

  private static SessionOptions options(ScriptedModel model) {
    return SessionOptions.newBuilder()
        .withModel(model)
        .withSessionId(SID)
        .withStore(new PgSessionStore(PgTestSupport.pgConfig()))
        .build();
  }

  private static List<SessionEntry> messages(int count) {
    return IntStream.range(0, count).mapToObj(i -> added(Message.user("m" + i))).toList();
  }

  private static Object[] row(long seq) {
    var entry = added(Message.user("held"));
    return new Object[] {
      SID,
      seq,
      "message",
      entry.runId(),
      SessionEntryJson.write(entry),
      entry.at().atOffset(ZoneOffset.UTC)
    };
  }

  private static CompletableFuture<Void> appendInBackground(
      PgSessionStore store, long expectedLastSeq, int count) {
    return CompletableFuture.runAsync(
        () -> store.append(SID, expectedLastSeq, messages(count)),
        task -> Thread.ofVirtual().start(task));
  }

  private static CompletableFuture<Boolean> deleteInBackground(PgSessionStore store) {
    return CompletableFuture.supplyAsync(
        () -> store.delete(SID), task -> Thread.ofVirtual().start(task));
  }

  /**
   * Wait until {@code count} backends wait on a lock, the last of them {@code task}'s; a task that
   * finished instead never blocked, which the test's assertions then report.
   */
  private static void awaitLockWaiters(long count, CompletableFuture<?> task) {
    Await.until(
        count + " backends waiting on a lock, or the task done",
        () ->
            task.isDone()
                || PgTestSupport.dbClient()
                        .execute()
                        .get(
                            "SELECT COUNT(*) AS n FROM pg_stat_activity"
                                + " WHERE wait_event_type = 'Lock'"
                                + " AND datname = current_database()")
                        .map(row -> row.column("n").getLong())
                        .orElseThrow()
                    == count);
  }

  private static long rowCount() {
    return PgTestSupport.dbClient()
        .execute()
        .get("SELECT COUNT(*) AS n FROM helios_session_entries")
        .map(row -> row.column("n").getLong())
        .orElseThrow();
  }
}
