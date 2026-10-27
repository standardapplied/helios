/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.session.test.SessionStoreContract;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class SessionEntryJsonTest {

  private static final Instant AT = Instant.parse("2026-10-27T10:00:00.123456Z");

  @Test
  void everyEntryReadsBackAsWritten() {
    var history = SessionStoreContract.richHistory();
    for (var message : history) {
      var added = new SessionEntry.MessageAdded("run-1", AT, message);
      assertEquals(added, SessionEntryJson.read(SessionEntryJson.write(added)));
    }
    var replaced = new SessionEntry.HistoryReplaced("run-2", AT, history);
    assertEquals(replaced, SessionEntryJson.read(SessionEntryJson.write(replaced)));
    var emptied = new SessionEntry.HistoryReplaced("run-3", AT, List.of());
    assertEquals(emptied, SessionEntryJson.read(SessionEntryJson.write(emptied)));
  }

  @Test
  void anEntryIsWrittenInItsDocumentedForm() {
    var json =
        SessionEntryJson.write(
            new SessionEntry.HistoryReplaced("run-1", AT, List.of(Message.user("s"))));

    assertEquals(
        "{\"type\":\"replaced\",\"runId\":\"run-1\",\"at\":\"2026-10-27T10:00:00.123456Z\","
            + "\"history\":[",
        json.substring(0, json.indexOf('[') + 1));
  }

  @Test
  void anUnknownTypeFailsNamingIt() {
    var thrown =
        assertThrows(
            SessionStoreException.class,
            () ->
                SessionEntryJson.read(
                    "{\"type\":\"parent\",\"runId\":\"run-1\",\"at\":\"2026-10-27T10:00:00Z\"}"));

    assertEquals("unknown session entry type 'parent'", thrown.getMessage());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "not json",
        "[]",
        "{\"runId\":\"run-1\",\"at\":\"2026-10-27T10:00:00Z\"}",
        "{\"type\":\"message\",\"runId\":\"run-1\",\"at\":\"yesterday\",\"message\":{}}",
        "{\"type\":\"message\",\"runId\":\"run-1\",\"at\":\"2026-10-27T10:00:00Z\"}",
        "{\"type\":\"replaced\",\"runId\":\"run-1\",\"at\":\"2026-10-27T10:00:00Z\",\"history\":3}"
      })
  void jsonThatIsNoEntryFailsAsAStoreFailure(String json) {
    var thrown = assertThrows(SessionStoreException.class, () -> SessionEntryJson.read(json));

    assertEquals(
        "not a session entry: " + thrown.getCause().getClass().getName(), thrown.getMessage());
  }

  @Test
  void aBatchReadsBackAsWritten() {
    var batch =
        new SessionEntryJson.Batch(
            4,
            List.of(
                new SessionEntry.MessageAdded("run-1", AT, Message.user("a")),
                new SessionEntry.HistoryReplaced("run-1", AT, List.of(Message.user("s")))));

    assertEquals(batch, SessionEntryJson.readBatch(SessionEntryJson.write(batch)));
    assertInstanceOf(
        SessionStoreException.class,
        assertThrows(RuntimeException.class, () -> SessionEntryJson.readBatch("{\"seq\":1}")));
  }
}
