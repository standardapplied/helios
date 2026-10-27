/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.session.test.SessionStoreContract;
import java.util.List;
import org.junit.jupiter.api.Test;

final class InMemorySessionStoreTest extends SessionStoreContract {

  @Override
  protected SessionStore store() {
    return new InMemorySessionStore();
  }

  @Test
  void entriesAreTheWholeLogAndDeleteRemovesIt() {
    var store = new InMemorySessionStore();
    var log =
        List.of(added(Message.user("a")), replaced(Message.user("s")), added(Message.user("b")));
    store.append(SID, 0, log);

    assertEquals(log, store.entries(SID));
    assertTrue(store.delete(SID));
    assertFalse(store.delete(SID));
    assertEquals(List.of(), store.entries(SID));
    assertEquals(0, store.load(SID).lastSeq());
  }
}
