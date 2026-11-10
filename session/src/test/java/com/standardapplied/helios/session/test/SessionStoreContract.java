/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.FileReference;
import com.standardapplied.helios.core.model.InlineFile;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Role;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.session.store.MessageOrigin;
import com.standardapplied.helios.session.store.SessionConflictException;
import com.standardapplied.helios.session.store.SessionEntry;
import com.standardapplied.helios.session.store.SessionStore;
import com.standardapplied.helios.session.store.SessionStoreException;
import com.standardapplied.helios.session.store.StoredSession;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.testing.ScriptedModel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The cases every {@link SessionStore} passes, whatever holds the log: a store's test extends this
 * class and supplies a fresh, empty store per test.
 */
public abstract class SessionStoreContract {

  /** The session the cases write. */
  protected static final String SID = "chat-1";

  private static final Instant AT = Instant.parse("2026-10-27T10:00:00Z");

  /**
   * A fresh store holding no session.
   *
   * @return the store under test
   */
  protected abstract SessionStore store();

  /**
   * A message added by run {@code run-1}.
   *
   * @param message the message
   * @return the entry
   */
  protected static SessionEntry added(Message message) {
    return new SessionEntry.MessageAdded("run-1", AT, message);
  }

  /**
   * A history replaced by run {@code run-1}.
   *
   * @param history the new view
   * @return the entry
   */
  protected static SessionEntry replaced(Message... history) {
    return new SessionEntry.HistoryReplaced("run-1", AT, List.of(history));
  }

  /**
   * A history holding what a provider round trip carries: inline bytes, file references, numeric,
   * {@code null}, nested list and map tool arguments, provider metadata and a tool result; decimals
   * a {@link Double} cannot hold, and text with lone UTF-16 surrogates and a supplementary
   * character.
   *
   * @return the history
   */
  public static List<Message> richHistory() {
    var arguments = new LinkedHashMap<String, Object>();
    arguments.put("int", 7);
    arguments.put("long", 5_000_000_000L);
    arguments.put("double", 1.5);
    arguments.put("doubles", List.of(0.1, -0.0, Double.MIN_VALUE, Double.MAX_VALUE));
    arguments.put("precise", new BigDecimal("0.12345678901234567890123456789"));
    arguments.put("huge", new BigDecimal("1E+400"));
    arguments.put("tiny", new BigDecimal("-1E-400"));
    arguments.put("none", null);
    arguments.put("list", List.of(1, "two", Map.of("three", true)));
    arguments.put("map", Map.of("nested", List.of()));
    var call = new ToolCall("call-1", "lookup", arguments);
    return List.of(
        Message.user(
            "look at these", List.of(InlineFile.of(new byte[] {0, 1, (byte) 255}, "image/png"))),
        Message.newBuilder()
            .withRole(Role.USER)
            .withContent("and this")
            .withFileReferences(
                List.of(new FileReference("https://example.com/a.pdf", "application/pdf")))
            .build(),
        Message.assistant(
            "thinking done",
            List.of(call),
            Map.of("anthropic.rawContent", "[{\"type\":\"thinking\",\"signature\":\"sig\"}]")),
        Message.tool("call-1", "lookup", "found"),
        Message.user("high \uD800, low \uDC00, pair \uD83D\uDE00, accent \u00E9"),
        Message.assistant("done"));
  }

  @Test
  void anUnknownSessionLoadsEmptyAtZero() {
    assertEquals(new StoredSession(SID, 0, List.of()), store().load(SID));
  }

  @Test
  void appendsAssignDenseSequenceNumbers() {
    var store = store();
    store.append(SID, 0, List.of(added(Message.user("a")), added(Message.assistant("b"))));
    store.append(SID, 2, List.of(added(Message.user("c"))));

    assertEquals(
        new StoredSession(
            SID, 3, List.of(Message.user("a"), Message.assistant("b"), Message.user("c"))),
        store.load(SID));
  }

  @Test
  void aStaleAppendConflictsAndWritesNothing() {
    var store = store();
    store.append(SID, 0, List.of(added(Message.user("a"))));

    var behind =
        assertThrows(
            SessionConflictException.class,
            () -> store.append(SID, 0, List.of(added(Message.user("b")))));
    assertThrows(
        SessionConflictException.class,
        () -> store.append(SID, 2, List.of(added(Message.user("c")))));

    assertEquals(
        "session chat-1 was written by another writer: the append expected last sequence number"
            + " 0, the store holds 1",
        behind.getMessage());
    assertEquals(new StoredSession(SID, 1, List.of(Message.user("a"))), store.load(SID));
  }

  @Test
  void loadFoldsAcrossTheLastHistoryReplaced() {
    var store = store();
    store.append(
        SID,
        0,
        List.of(
            added(Message.user("a")),
            added(Message.assistant("b")),
            replaced(Message.user("summary"))));
    store.append(SID, 3, List.of(added(Message.user("c"))));

    assertEquals(
        new StoredSession(SID, 4, List.of(Message.user("summary"), Message.user("c"))),
        store.load(SID));
  }

  @Test
  void aRichHistoryRoundTripsExactly() {
    var store = store();
    var history = richHistory();
    store.append(SID, 0, history.stream().map(SessionStoreContract::added).toList());
    store.append(SID, history.size(), List.of(replaced(history.toArray(Message[]::new))));

    assertEquals(new StoredSession(SID, history.size() + 1, history), store.load(SID));
  }

  @Test
  void aRunEndedRoundTripsAndIsTheLastRunEndedOnlyWhileItIsTheLastEntry() {
    var store = store();
    var ended = new SessionEntry.RunEnded("run-1", AT, "Success", Optional.of("done"));
    var correction =
        Message.newBuilder()
            .withRole(Role.USER)
            .withContent("fix the fields")
            .withMetadata(MessageOrigin.SCHEMA_CORRECTION.metadata())
            .build();
    store.append(SID, 0, List.of(added(Message.user("a")), added(correction), ended));

    var loaded = store.load(SID);
    store.append(SID, 3, List.of(added(Message.user("b"))));

    assertEquals(
        new StoredSession(SID, 3, List.of(Message.user("a"), correction), Optional.of(ended)),
        loaded);
    assertEquals(
        Optional.of(MessageOrigin.SCHEMA_CORRECTION), MessageOrigin.of(loaded.history().get(1)));
    assertEquals(Optional.empty(), store.load(SID).lastRunEnded());
  }

  @Test
  void aRunEndedAfterTheLastHistoryReplacedIsRead() {
    var store = store();
    var ended = new SessionEntry.RunEnded("run-2", AT, "Cancelled", Optional.empty());
    store.append(SID, 0, List.of(added(Message.user("a"))));
    store.append(SID, 1, List.of(replaced(Message.user("summary")), ended));

    assertEquals(
        new StoredSession(SID, 3, List.of(Message.user("summary")), Optional.of(ended)),
        store.load(SID));
  }

  @Test
  void sessionsAreKeptApart() {
    var store = store();
    store.append(SID, 0, List.of(added(Message.user("a"))));
    store.append("chat-2", 0, List.of(added(Message.user("b"))));

    assertEquals(List.of(Message.user("b")), store.load("chat-2").history());
  }

  @Test
  void ofTwoAppendsAgainstOneSequenceNumberExactlyOneSucceeds() {
    var store = store();
    var start = new CountDownLatch(1);
    var appends = new ArrayList<CompletableFuture<Void>>();
    for (var text : List.of("first", "second")) {
      var append = new CompletableFuture<Void>();
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  start.await();
                  store.append(SID, 0, List.of(added(Message.user(text))));
                  append.complete(null);
                } catch (InterruptedException | RuntimeException e) {
                  append.completeExceptionally(e);
                }
              });
      appends.add(append);
    }
    start.countDown();

    var outcomes = new ArrayList<String>();
    for (var append : appends) {
      var failure =
          Await.value("an append", append.handle((done, thrown) -> Optional.ofNullable(thrown)));
      outcomes.add(failure.map(thrown -> thrown.getClass().getSimpleName()).orElse("appended"));
    }

    assertEquals(
        List.of("SessionConflictException", "appended"), outcomes.stream().sorted().toList());
    assertEquals(1, store.load(SID).lastSeq());
  }

  @Test
  void anEmptyBatchIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> store().append(SID, 0, List.of()));
  }

  @Test
  void anIdOutsideTheRuleIsRejected() {
    var store = store();
    assertThrows(IllegalArgumentException.class, () -> store.load("../escape"));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.append("../escape", 0, List.of(added(Message.user("a")))));
  }

  /**
   * A run stopped at any of its store writes, as a crash leaves the log, never leaves a tool call
   * without its result: the run writes after a whole turn, its tool results included, and at its
   * end after answering every open call, so the session reopened over the store takes a new
   * message, and the request that answers it pairs every tool call with its result. The run writes
   * three batches: the first turn, the second, and the last with the run's end.
   *
   * @param writes how many of the run's writes the store took before it stopped
   */
  @ParameterizedTest(name = "stopped after {0} writes")
  @ValueSource(ints = {0, 1, 2, 3})
  void aSessionReopenedAfterItsRunStoppedAtAnyWriteTakesANewMessage(int writes) {
    var store = store();
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(
                new ToolCall("c-1", "echo", Map.of()), new ToolCall("c-2", "echo", Map.of()))
            .withToolCallsTurn(new ToolCall("c-3", "echo", Map.of()))
            .withTextTurn("done")
            .build();
    try (var session = AgentSession.open(stored(model, new StopsAfter(store, writes)))) {
      Await.value("the stopped run", session.send("start"));
    }
    assertEquals(writes == 3, store.load(SID).lastRunEnded().isPresent());

    var next = ScriptedModel.newBuilder().withTextTurn("resumed").build();
    try (var session = AgentSession.open(stored(next, store))) {
      assertInstanceOf(
          ResultMessage.Success.class, Await.value("the next run", session.send("continue")));
    }
    var request = next.calls().getFirst();
    assertEquals(
        List.of(0L, 2L, 3L, 3L).get(writes),
        request.stream().filter(message -> message.toolCallId() != null).count(),
        "the tool results the stopped run stored");
    for (var i = 0; i < request.size(); i++) {
      for (var call : Optional.ofNullable(request.get(i).toolCalls()).orElse(List.of())) {
        var answered =
            request.subList(i + 1, request.size()).stream()
                .anyMatch(message -> call.id().equals(message.toolCallId()));
        assertTrue(answered, "call " + call.id() + " has no result in " + request);
      }
    }
  }

  private static SessionOptions stored(ScriptedModel model, SessionStore store) {
    var echo =
        Tool.newBuilder()
            .withName("echo")
            .withDescription("echo")
            .withExecutor((arguments, context) -> ToolResult.success("echoed"))
            .build();
    return SessionOptions.newBuilder()
        .withModel(model)
        .withSessionId(SID)
        .withStore(store)
        .withTool(ToolBinding.newBuilder(echo).withCategory(ToolCategory.READ).build())
        .build();
  }

  /** A store that takes {@code writes} appends and refuses every later one, as a crash would. */
  private record StopsAfter(SessionStore store, AtomicInteger left) implements SessionStore {

    StopsAfter(SessionStore store, int writes) {
      this(store, new AtomicInteger(writes));
    }

    @Override
    public StoredSession load(String sessionId) {
      return store.load(sessionId);
    }

    @Override
    public void append(String sessionId, long expectedLastSeq, List<SessionEntry> entries) {
      if (left.getAndDecrement() <= 0) {
        throw new SessionStoreException("stopped", null);
      }
      store.append(sessionId, expectedLastSeq, entries);
    }
  }
}
