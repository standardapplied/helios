/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.examples.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.BlockingModel;
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
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.session.SummarizingCompactor;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.files.GlobTool;
import com.standardapplied.helios.session.files.GrepTool;
import com.standardapplied.helios.session.files.LsTool;
import com.standardapplied.helios.session.files.ReadTool;
import com.standardapplied.helios.session.files.WorkspaceRoot;
import com.standardapplied.helios.session.test.EventCollector;
import com.standardapplied.helios.session.test.QuestionAnswers;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real-API integration test for context compaction against Gemini.
 *
 * <p>The unit tests in {@code SummarizingCompactorTest} prove the compactor's wire mechanics
 * against a mock {@code Model}. This test proves the harder claim: the compacted history the loop
 * ships (the summary, then the pending user turn) is something a real provider actually accepts on
 * the very next turn — message-role ordering and all the other invariants that surface only in a
 * live request.
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
      new BlockingModel() {
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
                GeminiModelId.GEMINI_3_8_FLASH.id(),
                ModelConfig.newBuilder()
                    .withApiKey(System.getenv("GEMINI_API_KEY"))
                    .withToolChoice(
                        ToolChoice.required(
                            ReadTool.NAME, LsTool.NAME, GlobTool.NAME, GrepTool.NAME))
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
    var run = runNotesTask(tmp);
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

  private record Run(ResultMessage result, EventCollector events) {}

  /**
   * Run {@link #NOTES_TASK} over a seeded workspace with a compactor that fires mid-run: each tool
   * round adds two messages, and three messages cross the watermark.
   */
  private static Run runNotesTask(Path tmp) throws IOException {
    seedFiles(tmp);
    var ws = WorkspaceRoot.of(tmp);
    var tools =
        List.of(
            ReadTool.binding(ws), LsTool.binding(ws), GlobTool.binding(ws), GrepTool.binding(ws));

    var compactor = SummarizingCompactor.newBuilder(SUMMARY_MODEL).build();

    // At 100 tokens a message the 285-token watermark trips from three messages on: the first tool
    // round's user, assistant and tool messages become one summary.
    var limits = SessionLimits.newBuilder().withMaxContextTokens(300L).withMaxTurns(8).build();

    var options =
        SessionOptions.newBuilder()
            .withModel(forced)
            .withTools(tools)
            .withTokenCounter(history -> 100L * history.size())
            .withContextCompactor(compactor)
            .withLimits(limits)
            .build();

    try (var session = AgentSession.open(options)) {
      var events = new EventCollector(QuestionAnswers.selecting(session, "Deny"));
      events.read(session.events());
      var result = session.runBlocking(UserMessage.text(NOTES_TASK));
      events.awaitRunEnded();
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
