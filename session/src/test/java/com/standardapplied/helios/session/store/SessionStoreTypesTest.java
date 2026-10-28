/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.model.Message;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The value types of the store SPI: what they accept and the one load rule. */
final class SessionStoreTypesTest {

  private static final Instant AT = Instant.parse("2026-10-27T10:00:00Z");

  private static SessionEntry added(String text) {
    return new SessionEntry.MessageAdded("run-1", AT, Message.user(text));
  }

  private static SessionEntry replaced(String text) {
    return new SessionEntry.HistoryReplaced("run-1", AT, List.of(Message.user(text)));
  }

  @Test
  void foldIsTheLastReplacedViewFollowedByEveryMessageAddedAfterIt() {
    var tail = List.of(added("a"), added("b"), replaced("s"), added("c"));

    assertEquals(
        List.of(Message.user("s"), Message.user("c")),
        StoredSession.fold("chat-1", 4, tail).history());
    assertEquals(
        List.of(Message.user("a"), Message.user("b")),
        StoredSession.fold("chat-1", 2, tail.subList(0, 2)).history());
    assertEquals(
        List.of(Message.user("s"), Message.user("c")),
        StoredSession.fold("chat-1", 4, tail.subList(2, 4)).history());
  }

  @Test
  void aStoredSessionValidatesAndCopies() {
    var history = new ArrayList<>(List.of(Message.user("a")));
    var stored = new StoredSession("chat-1", 1, history);
    history.add(Message.user("b"));

    assertEquals(List.of(Message.user("a")), stored.history());
    assertThrows(IllegalArgumentException.class, () -> new StoredSession(" ", 0, List.of()));
    assertThrows(IllegalArgumentException.class, () -> new StoredSession("chat-1", -1, List.of()));
    assertThrows(NullPointerException.class, () -> new StoredSession("chat-1", 0, null));
    assertThrows(NullPointerException.class, () -> StoredSession.fold("chat-1", 0, null));
    var withNull = new ArrayList<SessionEntry>();
    withNull.add(null);
    assertThrows(NullPointerException.class, () -> StoredSession.fold("chat-1", 0, withNull));
  }

  @Test
  void entriesValidateTheirComponents() {
    var message = Message.user("a");
    assertThrows(
        IllegalArgumentException.class, () -> new SessionEntry.MessageAdded(" ", AT, message));
    assertThrows(
        NullPointerException.class, () -> new SessionEntry.MessageAdded("run-1", null, message));
    assertThrows(
        NullPointerException.class, () -> new SessionEntry.MessageAdded("run-1", AT, null));
    assertThrows(
        IllegalArgumentException.class, () -> new SessionEntry.HistoryReplaced(" ", AT, List.of()));
    assertThrows(
        NullPointerException.class,
        () -> new SessionEntry.HistoryReplaced("run-1", null, List.of()));
    assertThrows(
        NullPointerException.class, () -> new SessionEntry.HistoryReplaced("run-1", AT, null));
    var history = new ArrayList<>(List.of(message));
    var replaced = new SessionEntry.HistoryReplaced("run-1", AT, history);
    history.clear();
    assertEquals(List.of(message), replaced.history());
  }

  @Test
  void aStoreFailureNeedsAMessage() {
    assertThrows(NullPointerException.class, () -> new SessionStoreException(null, null));
  }

  @Test
  void theSessionIdRuleAcceptsAndRefuses() {
    for (var id : List.of("sess-1", "member:42", "a.b_c-1", "x".repeat(128))) {
      assertEquals(id, SessionStore.requireValidId(id));
    }
    for (var id : List.of("", "../x", "a/b", ".hidden", "x".repeat(129), "a b", "é")) {
      assertThrows(IllegalArgumentException.class, () -> SessionStore.requireValidId(id), id);
    }
    assertThrows(NullPointerException.class, () -> SessionStore.requireValidId(null));
  }
}
