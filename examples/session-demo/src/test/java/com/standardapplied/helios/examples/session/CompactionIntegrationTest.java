/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */
package ai.singlr.examples.session;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.Model;
import ai.singlr.core.model.ModelConfig;
import ai.singlr.gemini.GeminiModelId;
import ai.singlr.gemini.GeminiProvider;
import ai.singlr.session.AgentSession;
import ai.singlr.session.DropMiddleToolResultsCompactor;
import ai.singlr.session.QueryEvent;
import ai.singlr.session.ResultMessage;
import ai.singlr.session.SessionLimits;
import ai.singlr.session.SessionOptions;
import ai.singlr.session.UserMessage;
import ai.singlr.session.files.GlobTool;
import ai.singlr.session.files.GrepTool;
import ai.singlr.session.files.InMemoryFileTracker;
import ai.singlr.session.files.LsTool;
import ai.singlr.session.files.ReadTool;
import ai.singlr.session.files.WorkspaceRoot;
import ai.singlr.session.hooks.CompactionPayload;
import ai.singlr.session.hooks.HookOutcome;
import ai.singlr.session.hooks.PostCompactHook;
import ai.singlr.session.hooks.PreCompactHook;
import ai.singlr.session.tools.ToolRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real-API integration test for context compaction against Gemini.
 *
 * <p>The unit tests in {@code DropMiddleToolResultsCompactorTest} prove the compactor's wire
 * mechanics against a mock {@code Model}. This test proves the harder claim: the compacted history
 * the loop ships ({@code head + summary + tail}) is something a real provider actually accepts on
 * the very next turn — tool-call/tool-result alignment, message-role ordering, and all the other
 * invariants that surface only in a live request.
 *
 * <p>Strategy: drive a session past the {@code 0.95 × maxContextTokens} compaction watermark by
 * running a multi-turn tool-use loop with an artificially small {@code maxContextTokens} cap, then
 * assert that {@link QueryEvent.ContextEdited} fires AND the session reaches a clean terminal
 * (which is only possible if Gemini accepted the post-compaction request body).
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
final class CompactionIntegrationTest {

  private static Model model;

  @BeforeAll
  static void setUp() {
    var apiKey = System.getenv("GEMINI_API_KEY");
    var config = ModelConfig.newBuilder().withApiKey(apiKey).build();
    model = new GeminiProvider().create(GeminiModelId.GEMINI_3_5_FLASH.id(), config);
  }

  @AfterAll
  static void tearDown() throws Exception {
    if (model != null) {
      model.close();
    }
  }

  @Test
  void compactedHistoryIsAcceptedByProvider(@TempDir Path tmp) throws Exception {
    seedFiles(tmp);
    var ws = WorkspaceRoot.of(tmp);
    var tracker = InMemoryFileTracker.create();
    var tools =
        new ToolRegistry(
            List.of(
                ReadTool.binding(ws, tracker),
                LsTool.binding(ws),
                GlobTool.binding(ws),
                GrepTool.binding(ws)));

    // Tight head/tail so the compactor produces a real shrink with a modest history depth.
    // Default 3/20 needs > 23 messages before any middle exists to summarise; that's a lot of
    // round-trips against a real API for one CI test. 1/1 means a 3+ message history already has
    // a non-empty middle.
    var compactor =
        DropMiddleToolResultsCompactor.newBuilder(model)
            .withHeadPreserved(1)
            .withTailPreserved(1)
            .build();

    // Artificially small context window so the cumulative tokens cross the 0.95 watermark within
    // a few turns of natural Gemini output. The char-based TokenCounter is conservative — short
    // assistant replies plus tool args/results land in the few-hundred-token range across 4–6
    // turns, so 400 is tight enough that the watermark trips reliably without starving the very
    // first round-trip. The 6 turn cap is the belt-and-braces ceiling: if anything misbehaves the
    // session terminates in seconds with a clean ErrorMaxTurns.
    var limits = SessionLimits.newBuilder().withMaxContextTokens(300L).withMaxTurns(8).build();

    var options =
        SessionOptions.newBuilder()
            .withModel(model)
            .withTools(tools)
            .withContextCompactor(compactor)
            .withLimits(limits)
            .build();

    var events = new CopyOnWriteArrayList<QueryEvent>();
    try (var session = AgentSession.create(options)) {
      session.events().subscribe(collector(events));
      // Multi-step task. Tool calls + tool results force the conversation history to grow several
      // messages per turn, so the cumulative token count crosses the small watermark cleanly.
      var prompt =
          "Do these steps one at a time, calling tools as needed:\n"
              + "1. Call LS to list workspace contents.\n"
              + "2. Call Read on note1.txt and tell me its first line.\n"
              + "3. Call Read on note2.txt and tell me its first line.\n"
              + "4. Call Read on note3.txt and tell me its first line.\n"
              + "5. Reply with a one-line summary mentioning all three files.";
      var result = session.runBlocking(UserMessage.text(prompt));

      // Compaction must have actually fired (history shrank, ContextEdited emitted).
      var contextEditedEvents =
          events.stream().filter(e -> e instanceof QueryEvent.ContextEdited).toList();
      assertTrue(
          !contextEditedEvents.isEmpty(),
          () ->
              "expected ContextEdited to fire at least once with such a small maxContextTokens"
                  + " — none observed. Either the watermark math is broken or maxContextTokens is"
                  + " too lax for this task. Events: "
                  + summariseEventKinds(events));

      // ContextWarning must precede ContextEdited at least once (sticky semantics).
      assertTrue(
          events.stream().anyMatch(e -> e instanceof QueryEvent.ContextWarning),
          () -> "expected ContextWarning to fire before ContextEdited. Events: " + events.size());

      // The critical claim: AFTER compaction, the loop must have continued without a provider
      // rejection. A clean terminal proves Gemini accepted the post-compaction request body — if
      // tool-call alignment or message-role ordering broke during compaction, the provider would
      // 400 and we'd see an ErrorDuringExecution instead of Success/ErrorMaxTurns.
      assertTrue(
          result instanceof ResultMessage.Success || result instanceof ResultMessage.ErrorMaxTurns,
          () ->
              "session must reach a clean terminal post-compaction — provider rejection would"
                  + " surface as ErrorDuringExecution. Got: "
                  + result);

      // No Error stream events from the provider either.
      var providerErrors = events.stream().filter(e -> e instanceof QueryEvent.Error).toList();
      assertTrue(
          providerErrors.isEmpty(),
          () ->
              "no provider-level Error events expected after compaction; got "
                  + providerErrors.size());
    }
  }

  @Test
  void preAndPostCompactHooksFireAgainstRealProvider(@TempDir Path tmp) throws Exception {
    seedFiles(tmp);
    var ws = WorkspaceRoot.of(tmp);
    var tracker = InMemoryFileTracker.create();
    var tools =
        new ToolRegistry(
            List.of(
                ReadTool.binding(ws, tracker),
                LsTool.binding(ws),
                GlobTool.binding(ws),
                GrepTool.binding(ws)));
    var compactor =
        DropMiddleToolResultsCompactor.newBuilder(model)
            .withHeadPreserved(1)
            .withTailPreserved(1)
            .build();
    var limits = SessionLimits.newBuilder().withMaxContextTokens(300L).withMaxTurns(8).build();

    var preCompactFires = new AtomicInteger(0);
    var preCompactSawSize = new AtomicInteger(0);
    PreCompactHook preHook =
        (history, ctx) -> {
          preCompactFires.incrementAndGet();
          preCompactSawSize.set(history.size());
          return HookOutcome.cont();
        };

    var postCompactPayload = new AtomicReference<CompactionPayload>();
    var postCompactFires = new AtomicInteger(0);
    PostCompactHook postHook =
        (payload, ctx) -> {
          postCompactFires.incrementAndGet();
          postCompactPayload.set(payload);
          return HookOutcome.cont();
        };

    var options =
        SessionOptions.newBuilder()
            .withModel(model)
            .withTools(tools)
            .withContextCompactor(compactor)
            .withHook(preHook)
            .withHook(postHook)
            .withLimits(limits)
            .build();

    var events = new CopyOnWriteArrayList<QueryEvent>();
    try (var session = AgentSession.create(options)) {
      session.events().subscribe(collector(events));
      var prompt =
          "Do these steps one at a time, calling tools as needed:\n"
              + "1. Call LS to list workspace contents.\n"
              + "2. Call Read on note1.txt and tell me its first line.\n"
              + "3. Call Read on note2.txt and tell me its first line.\n"
              + "4. Call Read on note3.txt and tell me its first line.\n"
              + "5. Reply with a one-line summary mentioning all three files.";
      var result = session.runBlocking(UserMessage.text(prompt));

      // Clean terminal — proves the post-hook didn't break the loop.
      assertTrue(
          result instanceof ResultMessage.Success || result instanceof ResultMessage.ErrorMaxTurns,
          () -> "session must reach a clean terminal, got: " + result);

      // Pre-hook must have fired at least once (the compactor was invoked at least once).
      assertTrue(
          preCompactFires.get() >= 1,
          () -> "PreCompactHook must fire on every compactor invocation, got " + preCompactFires);
      assertTrue(
          preCompactSawSize.get() >= 3,
          () ->
              "PreCompactHook receives the pre-compaction history; with 3+ tool round-trips it"
                  + " should see at least 3 messages, got "
                  + preCompactSawSize);

      // Post-hook must have fired AT LEAST once (the compactor actually shrank the history at
      // least once — ContextEdited would have been emitted too).
      assertTrue(
          postCompactFires.get() >= 1,
          () ->
              "PostCompactHook must fire on every successful shrink. Either compaction never"
                  + " produced a real shrink or the wiring is broken. Fires: "
                  + postCompactFires
                  + ", events: "
                  + summariseEventKinds(events));
      var payload = postCompactPayload.get();
      assertTrue(payload != null, "PostCompactHook must have received a payload");
      assertTrue(
          payload.removedBlocks() > 0,
          () -> "PostCompactHook payload must report removed blocks, got " + payload);
      assertTrue(
          payload.historyAfter().size() < payload.historyBefore().size(),
          "PostCompactHook payload must reflect a real shrink");
    }
  }

  private static void seedFiles(Path tmp) throws IOException {
    Files.writeString(
        tmp.resolve("note1.txt"),
        "alpha first line.\nMore content padding for tokens.\n".repeat(8),
        StandardCharsets.UTF_8);
    Files.writeString(
        tmp.resolve("note2.txt"),
        "beta first line.\nMore content padding for tokens.\n".repeat(8),
        StandardCharsets.UTF_8);
    Files.writeString(
        tmp.resolve("note3.txt"),
        "gamma first line.\nMore content padding for tokens.\n".repeat(8),
        StandardCharsets.UTF_8);
  }

  private static String summariseEventKinds(List<QueryEvent> events) {
    var counts = new java.util.LinkedHashMap<String, Integer>();
    for (var e : events) {
      counts.merge(e.getClass().getSimpleName(), 1, Integer::sum);
    }
    return counts.toString();
  }

  private static Flow.Subscriber<QueryEvent> collector(CopyOnWriteArrayList<QueryEvent> sink) {
    return new Flow.Subscriber<>() {
      @Override
      public void onSubscribe(Flow.Subscription subscription) {
        subscription.request(Long.MAX_VALUE);
      }

      @Override
      public void onNext(QueryEvent item) {
        sink.add(item);
      }

      @Override
      public void onError(Throwable throwable) {}

      @Override
      public void onComplete() {}
    };
  }
}
