/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.examples.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.ToolChoice;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.gemini.GeminiModelId;
import com.standardapplied.helios.gemini.GeminiProvider;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.DropMiddleToolResultsCompactor;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.files.GlobTool;
import com.standardapplied.helios.session.files.GrepTool;
import com.standardapplied.helios.session.files.InMemoryFileTracker;
import com.standardapplied.helios.session.files.LsTool;
import com.standardapplied.helios.session.files.ReadTool;
import com.standardapplied.helios.session.files.WorkspaceRoot;
import com.standardapplied.helios.session.hooks.CompactionPayload;
import com.standardapplied.helios.session.hooks.Hook;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.PostCompactHook;
import com.standardapplied.helios.session.hooks.PreCompactHook;
import com.standardapplied.helios.session.test.CollectingSubscriber;
import com.standardapplied.helios.session.test.QuestionAnswers;
import com.standardapplied.helios.session.tools.ToolRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
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
 * running a multi-turn tool-use loop, then assert that {@link QueryEvent.ContextEdited} fires AND
 * the session reaches a clean terminal (which is only possible if Gemini accepted the
 * post-compaction request body). The session's model is forced to call a tool on every turn, so the
 * history grows by a tool round per turn whatever the model would have chosen, and the run always
 * ends at the turn limit. The token count is a fixed cost per message and the summary is a fixed
 * text, so when compaction fires does not depend on the model's tool arguments or summary wording.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
final class CompactionIntegrationTest {

  private static final String NOTES_TASK =
      "Do these steps one at a time, calling tools as needed:\n"
          + "1. Call LS to list workspace contents.\n"
          + "2. Call Read on note1.txt and tell me its first line.\n"
          + "3. Call Read on note2.txt and tell me its first line.\n"
          + "4. Call Read on note3.txt and tell me its first line.\n"
          + "5. Reply with a one-line summary mentioning all three files.";

  private static final Model SUMMARY_MODEL =
      new Model() {
        @Override
        public Response<Void> chat(List<Message> messages, List<Tool> tools) {
          return Response.newBuilder()
              .withContent("Earlier file-tool calls completed.")
              .withFinishReason(FinishReason.STOP)
              .withUsage(Response.Usage.of(0, 0))
              .build();
        }

        @Override
        public String id() {
          return "fixed-summary";
        }

        @Override
        public String provider() {
          return "testing";
        }
      };

  private static Model forced;

  @BeforeAll
  static void setUp() {
    forced =
        new GeminiProvider()
            .create(
                GeminiModelId.GEMINI_3_5_FLASH.id(),
                ModelConfig.newBuilder()
                    .withApiKey(System.getenv("GEMINI_API_KEY"))
                    .withToolChoice(ToolChoice.any())
                    .build());
  }

  @AfterAll
  static void tearDown() throws Exception {
    if (forced != null) {
      forced.close();
    }
  }

  @Test
  void compactedHistoryIsAcceptedByProvider(@TempDir Path tmp) throws Exception {
    var run = runNotesTask(tmp, List.of());
    var events = run.events();

    assertFalse(
        events.eventsOf(QueryEvent.ContextEdited.class).isEmpty(),
        () ->
            "expected ContextEdited once the history crossed the watermark — none observed."
                + " Events: "
                + summariseEventKinds(events.events()));

    assertFalse(
        events.eventsOf(QueryEvent.ContextWarning.class).isEmpty(),
        () ->
            "expected ContextWarning to fire before ContextEdited. Events: "
                + events.events().size());

    // A clean terminal proves Gemini accepted the post-compaction request body: had compaction
    // broken tool-call alignment or message-role ordering, the provider would 400 and the run would
    // end in ErrorDuringExecution instead of Success/ErrorMaxTurns.
    var result = run.result();
    assertTrue(
        result instanceof ResultMessage.Success || result instanceof ResultMessage.ErrorMaxTurns,
        () ->
            "session must reach a clean terminal post-compaction — provider rejection would"
                + " surface as ErrorDuringExecution. Got: "
                + result);

    var providerErrors = events.eventsOf(QueryEvent.Error.class);
    assertTrue(
        providerErrors.isEmpty(),
        () ->
            "no provider-level Error events expected after compaction; got "
                + providerErrors.size());
  }

  @Test
  void preAndPostCompactHooksFireAgainstRealProvider(@TempDir Path tmp) throws Exception {
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

    var run = runNotesTask(tmp, List.of(preHook, postHook));
    var result = run.result();

    assertTrue(
        result instanceof ResultMessage.Success || result instanceof ResultMessage.ErrorMaxTurns,
        () -> "session must reach a clean terminal, got: " + result);

    assertTrue(
        preCompactFires.get() >= 1,
        () -> "PreCompactHook must fire on every compactor invocation, got " + preCompactFires);
    assertTrue(
        preCompactSawSize.get() >= 3,
        () ->
            "PreCompactHook receives the pre-compaction history; with 3+ tool round-trips it"
                + " should see at least 3 messages, got "
                + preCompactSawSize);

    assertTrue(
        postCompactFires.get() >= 1,
        () ->
            "PostCompactHook must fire on every successful shrink. Either compaction never"
                + " produced a real shrink or the wiring is broken. Fires: "
                + postCompactFires
                + ", events: "
                + summariseEventKinds(run.events().events()));
    var payload = postCompactPayload.get();
    assertTrue(payload != null, "PostCompactHook must have received a payload");
    assertTrue(
        payload.removedBlocks() > 0,
        () -> "PostCompactHook payload must report removed blocks, got " + payload);
    assertTrue(
        payload.historyAfter().size() < payload.historyBefore().size(),
        "PostCompactHook payload must reflect a real shrink");
  }

  private record Run(ResultMessage result, CollectingSubscriber events) {}

  /**
   * Run {@link #NOTES_TASK} over a seeded workspace with {@code hooks} installed and a compactor
   * that fires mid-run: each tool round adds two messages, and five messages cross the watermark.
   */
  private static Run runNotesTask(Path tmp, List<Hook> hooks) throws IOException {
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

    // Default head/tail 3/20 needs > 23 messages before any middle exists to summarise, a lot of
    // live round-trips for one test; with 1/1 a 3+ message history already has a non-empty middle.
    var compactor =
        DropMiddleToolResultsCompactor.newBuilder(SUMMARY_MODEL)
            .withHeadPreserved(1)
            .withTailPreserved(1)
            .build();

    // At 100 tokens a message the 285-token watermark trips from three messages on, but head and
    // tail of one leave no middle until the second tool round makes five.
    var limits = SessionLimits.newBuilder().withMaxContextTokens(300L).withMaxTurns(8).build();

    var options =
        SessionOptions.newBuilder()
            .withModel(forced)
            .withTools(tools)
            .withTokenCounter(history -> 100L * history.size())
            .withContextCompactor(compactor)
            .withHooks(hooks)
            .withLimits(limits)
            .build();

    try (var session = AgentSession.create(options)) {
      var events = new CollectingSubscriber(QuestionAnswers.selecting(session, "Deny"));
      session.events().subscribe(events);
      var result = session.runBlocking(UserMessage.text(NOTES_TASK));
      events.awaitDone();
      return new Run(result, events);
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
    var counts = new LinkedHashMap<String, Integer>();
    for (var e : events) {
      counts.merge(e.getClass().getSimpleName(), 1, Integer::sum);
    }
    return counts.toString();
  }
}
