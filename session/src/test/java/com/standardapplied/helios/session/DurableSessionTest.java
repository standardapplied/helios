/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static com.standardapplied.helios.session.EndingSessions.binding;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.OnUserMessageHook;
import com.standardapplied.helios.session.hooks.PreModelTurnHook;
import com.standardapplied.helios.session.hooks.PreToolUseHook;
import com.standardapplied.helios.session.store.InMemorySessionStore;
import com.standardapplied.helios.session.store.SessionConflictException;
import com.standardapplied.helios.session.store.SessionEntry;
import com.standardapplied.helios.session.store.SessionStore;
import com.standardapplied.helios.session.store.SessionStoreException;
import com.standardapplied.helios.session.store.StoredSession;
import com.standardapplied.helios.session.test.EventCollector;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

/**
 * A session with a store loads the conversation stored under its id when it opens and appends one
 * batch per turn, so a handle opened later, in any process, carries the conversation on; a write
 * the store refuses ends the run and closes the session.
 */
final class DurableSessionTest {

  private static final String SID = "chat-1";
  private static final ToolCall ECHO = new ToolCall("c1", "echo", Map.of("v", "one"));
  private static final ToolCall ECHO_TWO = new ToolCall("c2", "echo", Map.of("v", "two"));
  private static final String SECRET = "s3cr3t-value-123";

  // ── options ─────────────────────────────────────────────────────────────

  @Test
  void withStoreRejectsNull() {
    var builder = SessionOptions.newBuilder();
    var ex = assertThrows(NullPointerException.class, () -> builder.withStore(null));
    assertEquals("store must not be null", ex.getMessage());
  }

  @Test
  void aStoreIsAbsentByDefaultAndKeptByToBuilder() {
    var store = new InMemorySessionStore();
    var plain = SessionOptions.newBuilder().withModel(ScriptedModel.newBuilder().build()).build();
    var stored = plain.toBuilder().withStore(store).build();

    assertEquals(Optional.empty(), plain.store());
    assertSame(store, stored.toBuilder().build().store().orElseThrow());
  }

  @Test
  void canonicalConstructorRejectsNullStore() {
    var o = SessionOptions.newBuilder().withModel(ScriptedModel.newBuilder().build()).build();
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new SessionOptions(
                    o.model(),
                    o.sessionId(),
                    o.limits(),
                    o.maxQueuedUserMessages(),
                    o.clock(),
                    o.tools(),
                    o.hooks(),
                    o.permission(),
                    o.memoryBackend(),
                    o.costCalculator(),
                    o.executionProvider(),
                    o.askUserQuestion(),
                    o.outputSchema(),
                    o.systemPrompt(),
                    o.tokenCounter(),
                    o.contextCompactor(),
                    o.secretRegistry(),
                    null));
    assertEquals("store must not be null", ex.getMessage());
  }

  // ── 1. reopen ────────────────────────────────────────────────────────────

  @Test
  void aReopenedSessionSeesTheStoredConversationUnderTheNewSystemPrompt() {
    var store = new InMemorySessionStore();
    var first = ScriptedModel.newBuilder().withToolCallsTurn(ECHO).withTextTurn("one").build();
    try (var session =
        AgentSession.open(
            options(first, store).withSystemPrompt("old").withTools(List.of(echo())).build())) {
      Await.value("run 1", session.send("first"));
    }
    var stored = messages(store.entries(SID));
    var second = ScriptedModel.newBuilder().withTextTurn("two").build();
    try (var session = AgentSession.open(options(second, store).withSystemPrompt("new").build())) {
      Await.value("run 2", session.send("second"));
    }
    var other = ScriptedModel.newBuilder().withTextTurn("hi").build();
    try (var session =
        AgentSession.open(
            options(other, store).withSessionId("chat-2").withSystemPrompt("new").build())) {
      Await.value("chat-2", session.send("hello"));
    }

    var run1 = first.calls().get(1);
    assertEquals(run1.subList(1, run1.size()), stored.subList(0, stored.size() - 1));
    assertEquals("echoed one", stored.get(2).content());
    assertEquals("one", stored.getLast().content());
    var expected = new ArrayList<Message>();
    expected.add(Message.system("new"));
    expected.addAll(stored);
    expected.add(Message.user("second"));
    assertEquals(expected, second.calls().getFirst());
    assertEquals(List.of(Message.system("new"), Message.user("hello")), other.calls().getFirst());
  }

  // ── 2. write rule ────────────────────────────────────────────────────────

  @Test
  void eachTurnAppendsWhatItAddedOnceAsOneBatch() {
    var store = new RecordingStore();
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(ECHO)
            .withToolCallsTurn(ECHO_TWO)
            .withTextTurn("done")
            .build();

    try (var session =
        AgentSession.open(
            options(model, store).withSystemPrompt("sys").withTools(List.of(echo())).build())) {
      Await.value("run", session.send("go"));
    }

    assertEquals(List.of(3, 2, 1), store.batches.stream().map(List::size).toList());
    var last = model.calls().getLast();
    var expected = new ArrayList<>(last.subList(1, last.size()));
    expected.add(messages(store.batches.getLast()).getFirst());
    assertEquals(expected, messages(store.inner.entries(SID)));
    assertEquals("done", expected.getLast().content());
  }

  @Test
  void aCompactionIsWrittenAsOneHistoryReplacedAndAppendsResumeAfterIt() {
    var store = new RecordingStore();
    var model = ScriptedModel.newBuilder().withTextTurn("one").withTextTurn("two").build();
    var compactions = new AtomicInteger();
    ContextCompactor compactor =
        (history, context) ->
            compactions.incrementAndGet() == 2
                ? CompactionResult.noOp(List.of(Message.system("sys"), Message.user("summary")))
                : CompactionResult.noOp(history);

    try (var session =
        AgentSession.open(
            overWatermark(options(model, store).withSystemPrompt("sys"), compactor).build())) {
      Await.value("run 1", session.send("first"));
      Await.value("run 2", session.send("second"));
    }

    assertEquals(List.of(2, 1, 2), store.batches.stream().map(List::size).toList());
    var replaced =
        assertInstanceOf(SessionEntry.HistoryReplaced.class, store.batches.get(1).get(0));
    assertEquals(List.of(Message.user("summary")), replaced.history());
    assertEquals(List.of(Message.user("second")), messages(store.batches.get(2)).subList(0, 1));
    assertEquals(
        List.of(Message.user("summary"), Message.user("second")),
        store.load(SID).history().subList(0, 2));
  }

  @Test
  void aMutateHistoryHookIsWrittenAsOneHistoryReplacedAndAppendsResumeAfterIt() {
    var store = new RecordingStore();
    var model =
        ScriptedModel.newBuilder()
            .withTextTurn("one")
            .withTextTurn("two")
            .withTextTurn("three")
            .build();
    var fires = new AtomicInteger();
    PreModelTurnHook rewriting =
        (history, ctx) ->
            fires.incrementAndGet() == 2
                ? HookOutcome.mutateHistory(
                    List.of(Message.system("sys"), Message.user("rewritten")))
                : HookOutcome.cont();

    try (var session =
        AgentSession.open(
            options(model, store).withSystemPrompt("sys").withHook(rewriting).build())) {
      Await.value("run 1", session.send("first"));
      Await.value("run 2", session.send("second"));
      Await.value("run 3", session.send("third"));
    }

    assertEquals(List.of(2, 1, 2), store.batches.stream().map(List::size).toList());
    var replaced =
        assertInstanceOf(SessionEntry.HistoryReplaced.class, store.batches.get(1).get(0));
    var request = model.calls().get(1);
    assertEquals(List.of(Message.system("sys"), Message.user("rewritten")), request);
    assertEquals(
        List.of(Message.user("rewritten"), "two"),
        List.of(replaced.history().get(0), replaced.history().get(1).content()));
    assertEquals(2, replaced.history().size());
    assertEquals(Message.user("third"), messages(store.batches.get(2)).getFirst());
  }

  @Test
  void aRunThatAddsNothingWritesNothing() {
    var store = new RecordingStore();
    var model = ScriptedModel.newBuilder().withTextTurn("unused").build();
    OnUserMessageHook blocking = (message, ctx) -> HookOutcome.block("not allowed");

    try (var session = AgentSession.open(options(model, store).withHook(blocking).build())) {
      var terminal = Await.value("run", session.send("blocked"));

      assertEquals(
          "NoUserMessage",
          assertInstanceOf(ResultMessage.ErrorDuringExecution.class, terminal).error().kind());
    }
    assertEquals(List.of(), store.batches);
  }

  // ── 4. conflict ──────────────────────────────────────────────────────────

  @Test
  void aSecondWriterConflictsEndsItsRunAndClosesItsSession() {
    var store = new InMemorySessionStore();
    var provider = new RecordingProvider(new CopyOnWriteArrayList<>());
    var model = ScriptedModel.newBuilder().withTextTurn("a reply").build();
    var late = ScriptedModel.newBuilder().withTextTurn("b reply").build();

    try (var winner = AgentSession.open(options(model, store).build());
        var loser =
            AgentSession.open(options(late, store).withExecutionProvider(provider).build())) {
      Await.value("winner", winner.send("a"));
      var terminal = Await.value("loser", loser.send("b"));

      var error = assertInstanceOf(ResultMessage.ErrorDuringExecution.class, terminal).error();
      assertEquals("SessionConflict", error.kind());
      assertEquals(
          "session chat-1 was written by another writer: the append expected last sequence"
              + " number 0, the store holds 2",
          error.message());
      var refused = assertThrows(IllegalStateException.class, () -> loser.send("again"));
      assertEquals("session is closed: its store refused a write", refused.getMessage());
      assertEquals(1, provider.ends.get());
    }
    assertEquals(
        List.of("a", "a reply"),
        messages(store.entries(SID)).stream().map(Message::content).toList());
  }

  // ── 5. store failures ────────────────────────────────────────────────────

  @Test
  void aFailingAppendEndsTheRunSessionStoreFailedWithItsMessageRedacted() {
    var store =
        new RecordingStore(index -> new SessionStoreException("disk refused " + SECRET, null));
    var secrets = new SecretRegistry();
    secrets.register("TOKEN", SECRET);
    var model = ScriptedModel.newBuilder().withTextTurn("reply").build();

    try (var session =
        AgentSession.open(options(model, store).withSecretRegistry(secrets).build())) {
      var error = errorOf(Await.value("run", session.send("go")));

      assertEquals("SessionStoreFailed", error.kind());
      assertEquals("disk refused <redacted:TOKEN>", error.message());
      assertThrows(IllegalStateException.class, () -> session.send("again"));
    }
  }

  @Test
  void aStoreThatThrowsAnythingElseFailsTheRunAsAStoreFailure() {
    var store = new RecordingStore(index -> new IllegalStateException("broken"));
    var model = ScriptedModel.newBuilder().withTextTurn("reply").build();

    try (var session = AgentSession.open(options(model, store).build())) {
      var error = errorOf(Await.value("run", session.send("go")));

      assertEquals("SessionStoreFailed", error.kind());
      assertEquals(
          RecordingStore.class.getName() + " failed to append to session chat-1", error.message());
    }
  }

  @Test
  void aWriteRefusedAtTheRunsEndReplacesTheRunsTerminalAndStoresNoHalfRound() {
    var store = new RecordingStore(index -> new SessionStoreException("full", null));
    var model = ScriptedModel.newBuilder().withToolCallsTurn(ECHO, ECHO_TWO).build();
    PreToolUseHook stopping = (call, ctx) -> HookOutcome.stop("halted");

    try (var session =
        AgentSession.open(
            options(model, store).withTools(List.of(echo())).withHook(stopping).build())) {
      var error = errorOf(Await.value("run", session.send("go")));

      assertEquals(List.of("SessionStoreFailed", "full"), List.of(error.kind(), error.message()));
    }
    assertEquals(new StoredSession(SID, 0, List.of()), store.load(SID));
  }

  @Test
  void anErrorAnAppendJoinsFromAFailedFutureFailsTheRunAsItselfWithoutARunEnded() {
    var atTurnEnd = new AssertionError("store worker failed at the turn's end");
    var atRunEnd = new AssertionError("store worker failed at the run's end");
    PreToolUseHook stopping = (call, ctx) -> HookOutcome.stop("halted");

    var turn =
        endedRun(
            options(
                ScriptedModel.newBuilder().withTextTurn("reply").build(),
                joiningFailed(atTurnEnd)));
    var run =
        endedRun(
            options(
                    ScriptedModel.newBuilder().withToolCallsTurn(ECHO).build(),
                    joiningFailed(atRunEnd))
                .withTools(List.of(echo()))
                .withHook(stopping));

    assertEquals(
        List.of(Map.entry(atTurnEnd, List.of()), Map.entry(atRunEnd, List.of())),
        List.of(turn, run));
  }

  @Test
  void aConflictIsTheTerminalEvenWhenAnInterruptArrivesWhileItIsRefused() {
    assertRefusalOutranksAnInterrupt(new SessionConflictException(SID, 0, 1), "SessionConflict");
  }

  @Test
  void aStoreFailureIsTheTerminalEvenWhenAnInterruptArrivesWhileItIsRefused() {
    assertRefusalOutranksAnInterrupt(new SessionStoreException("full", null), "SessionStoreFailed");
  }

  @Test
  void aFailingLoadFailsOpenBeforeTheExecutionProviderStarts() {
    var failure = new SessionStoreException("unreachable", null);
    var provider = new RecordingProvider(new CopyOnWriteArrayList<>());
    var store =
        new RecordingStore() {
          @Override
          public StoredSession load(String sessionId) {
            throw failure;
          }
        };
    var options =
        options(ScriptedModel.newBuilder().build(), store).withExecutionProvider(provider).build();

    assertSame(
        failure, assertThrows(SessionStoreException.class, () -> AgentSession.open(options)));
    assertEquals(0, provider.starts.get());
  }

  // ── 6. open round ────────────────────────────────────────────────────────

  @Test
  void aStoppedToolRoundIsWrittenWithItsNotRunResultsAsOneBatch() {
    var store = new RecordingStore();
    var model = ScriptedModel.newBuilder().withToolCallsTurn(ECHO, ECHO_TWO).build();
    PreToolUseHook stopping = (call, ctx) -> HookOutcome.stop("halted");

    try (var session =
        AgentSession.open(
            options(model, store).withTools(List.of(echo())).withHook(stopping).build())) {
      Await.value("run", session.send("go"));
    }

    assertEquals(1, store.batches.size());
    assertEquals(
        List.of(notRun(ECHO), notRun(ECHO_TWO)), messages(store.batches.getFirst()).subList(2, 4));
    assertEquals(4, store.batches.getFirst().size());
  }

  @Test
  void aReopenedSessionHoldsTheNotRunResultsOfTheRoundItsLastRunLeftOpen() {
    var store = new InMemorySessionStore();
    var first = ScriptedModel.newBuilder().withToolCallsTurn(ECHO, ECHO_TWO).build();
    PreToolUseHook stopping = (call, ctx) -> HookOutcome.stop("halted");
    try (var session =
        AgentSession.open(
            options(first, store).withTools(List.of(echo())).withHook(stopping).build())) {
      Await.value("run 1", session.send("go"));
    }
    var second = ScriptedModel.newBuilder().withTextTurn("after").build();
    try (var session =
        AgentSession.open(options(second, store).withTools(List.of(echo())).build())) {
      Await.value("run 2", session.send("next"));
    }

    var request = second.calls().getFirst();
    assertEquals(
        List.of(notRun(ECHO), notRun(ECHO_TWO), Message.user("next")),
        request.subList(2, request.size()));
  }

  // ── compaction on reopen ─────────────────────────────────────────────────

  @Test
  void aLoadedHistoryOverTheWatermarkIsCompactedBeforeTheFirstTurnAndWrittenReplaced() {
    var store = new RecordingStore();
    var at = Instant.parse("2026-10-27T10:00:00Z");
    store.inner.append(
        SID,
        0,
        List.of(
            new SessionEntry.MessageAdded("run-0", at, Message.user("old")),
            new SessionEntry.MessageAdded("run-0", at, Message.assistant("reply"))));
    var model = ScriptedModel.newBuilder().withTextTurn("answer").build();
    var compactions = new AtomicInteger();
    ContextCompactor compactor =
        (history, context) ->
            compactions.incrementAndGet() == 1
                ? CompactionResult.noOp(List.of(Message.user("summary"), history.getLast()))
                : CompactionResult.noOp(history);

    try (var session = AgentSession.open(overWatermark(options(model, store), compactor).build())) {
      Await.value("run", session.send("new"));
    }

    assertEquals(List.of(Message.user("summary"), Message.user("new")), model.calls().getFirst());
    var replaced =
        assertInstanceOf(SessionEntry.HistoryReplaced.class, store.batches.getFirst().getFirst());
    assertEquals(1, store.batches.size());
    assertEquals(1, store.batches.getFirst().size());
    assertEquals(
        List.of(Message.user("summary"), Message.user("new"), "answer"),
        List.of(
            replaced.history().get(0),
            replaced.history().get(1),
            replaced.history().get(2).content()));
  }

  // ── fixtures ─────────────────────────────────────────────────────────────

  /** A store that interrupts the run, then refuses its write with {@code refusal}. */
  private static void assertRefusalOutranksAnInterrupt(SessionStoreException refusal, String kind) {
    var session = new AtomicReference<AgentSession>();
    var store =
        new RecordingStore(
            index -> {
              session.get().interrupt();
              return refusal;
            });
    var model = ScriptedModel.newBuilder().withTextTurn("reply").build();

    try (var opened = AgentSession.open(options(model, store).build())) {
      session.set(opened);
      var error = errorOf(Await.value("run", opened.send("go")));

      assertEquals(List.of(kind, refusal.getMessage()), List.of(error.kind(), error.message()));
      assertThrows(IllegalStateException.class, () -> opened.send("again"));
    }
  }

  /** A store whose every append joins a future that failed with {@code error}. */
  private static RecordingStore joiningFailed(Error error) {
    return new RecordingStore(
        index -> CompletableFuture.<RuntimeException>failedFuture(error).join());
  }

  /** What the run {@code options} open fails with, and the {@code RunEnded} events it emitted. */
  private static Map.Entry<Throwable, List<QueryEvent.RunEnded>> endedRun(
      SessionOptions.Builder options) {
    var events = new EventCollector();
    try (var session = AgentSession.open(options.build())) {
      events.read(session.events());
      var failure = Await.failure("the result to fail", session.send("go"));
      session.close();
      events.awaitDone();
      return Map.entry(failure, events.eventsOf(QueryEvent.RunEnded.class));
    }
  }

  private static SessionOptions.Builder options(ScriptedModel model, SessionStore store) {
    return SessionOptions.newBuilder().withModel(model).withSessionId(SID).withStore(store);
  }

  /** {@code options} with every history counted over the compaction watermark. */
  private static SessionOptions.Builder overWatermark(
      SessionOptions.Builder options, ContextCompactor compactor) {
    return options
        .withLimits(SessionLimits.newBuilder().withMaxContextTokens(100).build())
        .withTokenCounter(messages -> 1_000)
        .withContextCompactor(compactor);
  }

  private static ToolBinding echo() {
    return binding("echo", (args, ctx) -> ToolResult.success("echoed " + args.get("v")));
  }

  private static Message notRun(ToolCall call) {
    return Message.tool(call.id(), call.name(), "[not run: the run ended before this call ran]");
  }

  private static SerializedError errorOf(ResultMessage terminal) {
    return assertInstanceOf(ResultMessage.ErrorDuringExecution.class, terminal).error();
  }

  private static List<Message> messages(List<SessionEntry> entries) {
    return entries.stream()
        .map(entry -> assertInstanceOf(SessionEntry.MessageAdded.class, entry).message())
        .toList();
  }

  /**
   * An in-memory store that records each batch it accepts and throws, for the append of each index,
   * what {@code failure} returns, if anything.
   */
  private static class RecordingStore implements SessionStore {

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
  }
}
