/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static com.standardapplied.helios.session.SkillSessions.FORMS;
import static com.standardapplied.helios.session.SkillSessions.FORMS_LOADED;
import static com.standardapplied.helios.session.SkillSessions.PDF;
import static com.standardapplied.helios.session.SkillSessions.PDF_ALREADY_LOADED;
import static com.standardapplied.helios.session.SkillSessions.PDF_LOADED;
import static com.standardapplied.helios.session.SkillSessions.compacting;
import static com.standardapplied.helios.session.SkillSessions.compactingAt;
import static com.standardapplied.helios.session.SkillSessions.echo;
import static com.standardapplied.helios.session.SkillSessions.load;
import static com.standardapplied.helios.session.SkillSessions.loadingPdf;
import static com.standardapplied.helios.session.SkillSessions.model;
import static com.standardapplied.helios.session.SkillSessions.read;
import static com.standardapplied.helios.session.SkillSessions.results;
import static com.standardapplied.helios.session.SkillSessions.run;
import static com.standardapplied.helios.session.SkillSessions.sent;
import static com.standardapplied.helios.session.SkillSessions.skill;
import static com.standardapplied.helios.session.SkillSessions.summary;
import static com.standardapplied.helios.session.SkillSessions.tagged;
import static com.standardapplied.helios.session.SkillSessions.tool;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.Role;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.SkillSessions.Recording;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.PreModelTurnHook;
import com.standardapplied.helios.session.store.InMemorySessionStore;
import com.standardapplied.helios.session.store.MessageOrigin;
import com.standardapplied.helios.session.store.SessionEntry;
import com.standardapplied.helios.session.store.SessionStore;
import com.standardapplied.helios.testing.ScriptedModel;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The skills a compaction puts back, whatever the compactor, in which order and with what text;
 * what a compaction that keeps them, changes nothing or reports spend gets; and what a reopened
 * session derives from its stored history.
 */
final class SkillCompactionSessionTest {

  @Test
  void theDefaultCompactorGetsTheActivationAsTheModelWasGivenItRedactedAndCut() {
    var secrets = new SecretRegistry();
    secrets.register("DB_PASSWORD", "hunter2-hunter2");
    var pdf =
        skill("pdf", "Extracts text from PDFs.", "Sign with hunter2-hunter2.\n" + "x".repeat(3_000))
            .build();
    var model =
        model(
            ScriptedModel.newBuilder()
                .withToolCallsTurn(load("c1", "pdf"))
                .withToolCallsTurn(echo("c2"))
                .withTextTurn("the gist")
                .withTextTurn("done"));

    run(
        model,
        compacting(model, pdf)
            .withTokenCounter(compactingAt(2))
            .withSecretRegistry(secrets)
            .withLimits(
                SessionLimits.newBuilder()
                    .withMaxContextTokens(100L)
                    .withMaxToolResultChars(1_024)
                    .build()));

    var given = model.calls().get(1).getLast();
    assertEquals(Message.tool("c1", "Skill", given.content()), given);
    assertTrue(
        given
            .content()
            .startsWith("<skill_content name=\"pdf\">\nSign with <redacted:DB_PASSWORD>."),
        given::content);
    assertTrue(given.content().contains("[output truncated: "), given::content);
    assertEquals(
        List.of(model.system(), tagged(given.content()), summary("the gist")),
        model.calls().getLast());
  }

  @Test
  void aSecondActivationInTheSameRoundAnswersThatTheSkillIsLoadedAndComesBackOnce() {
    var model =
        model(
            ScriptedModel.newBuilder()
                .withToolCallsTurn(load("c1", "pdf"), load("c2", "pdf"))
                .withTextTurn("the gist")
                .withTextTurn("done"));

    var results = results(run(model, compacting(model, PDF)));

    assertEquals(
        List.of(ToolResult.success(PDF_LOADED), ToolResult.success(PDF_ALREADY_LOADED)),
        List.of(results.get("c1"), results.get("c2")));
    assertEquals(
        List.of(model.system(), tagged(PDF_LOADED), summary("the gist")), model.calls().getLast());
  }

  @Test
  void aFileOfASkillPutBackCanStillBeRead() {
    var pdf = skill("pdf", "Extracts text from PDFs.", "").withFile("a.md", () -> "text").build();
    var model =
        model(
            ScriptedModel.newBuilder()
                .withToolCallsTurn(load("c1", "pdf"))
                .withTextTurn("the gist")
                .withToolCallsTurn(read("c2", "pdf", "a.md"))
                .withTextTurn("second gist")
                .withTextTurn("done"));

    var results = results(run(model, compacting(model, pdf)));

    assertEquals(MessageOrigin.SKILL, MessageOrigin.of(model.calls().get(2).get(1)).orElseThrow());
    assertEquals(
        ToolResult.success("<skill_file skill=\"pdf\" path=\"a.md\">\ntext\n</skill_file>"),
        results.get("c2"));
  }

  @Test
  void aCustomCompactorGetsTheSkillsBackAfterItsSystemMessage() {
    var model = loadingPdf();

    run(
        model,
        compacting(model, PDF)
            .withContextCompactor(
                (history, context) ->
                    CompactionResult.noOp(List.of(history.getFirst(), history.get(1)))));

    assertEquals(
        List.of(model.system(), tagged(PDF_LOADED), Message.user("go")), model.calls().getLast());
  }

  @Test
  void aCompactorThatKeepsNoSystemMessageGetsTheSkillsFirst() {
    var model = loadingPdf();

    run(
        model,
        compacting(model, PDF)
            .withContextCompactor(
                (history, context) -> CompactionResult.noOp(List.of(history.get(1)))));

    assertEquals(List.of(tagged(PDF_LOADED), Message.user("go")), model.calls().getLast());
  }

  @Test
  void aCompactorThatKeepsTheActivationGetsNoDuplicate() {
    var model = loadingPdf();

    run(
        model,
        compacting(model, PDF)
            .withTokenCounter(
                messages ->
                    messages.stream().anyMatch(m -> m.role() == Role.TOOL)
                            && messages.stream().anyMatch(m -> m.role() == Role.SYSTEM)
                        ? 96L
                        : 10L)
            .withContextCompactor(
                (history, context) ->
                    CompactionResult.noOp(List.copyOf(history.subList(1, history.size())))));

    assertEquals(
        List.of(Role.USER, Role.ASSISTANT, Role.TOOL),
        model.calls().getLast().stream().map(Message::role).toList());
  }

  @Test
  void aSkillThatWasLostComesBackBeforeOneTheCompactorKept() {
    var model =
        model(
            ScriptedModel.newBuilder()
                .withToolCallsTurn(load("c1", "forms"), load("c2", "pdf"))
                .withTextTurn("done"));

    run(
        model,
        compacting(model, PDF, FORMS)
            .withContextCompactor(
                (history, context) ->
                    CompactionResult.noOp(
                        List.of(history.getFirst(), tagged(FORMS_LOADED), history.get(1)))));

    assertEquals(
        List.of(model.system(), tagged(PDF_LOADED), tagged(FORMS_LOADED), Message.user("go")),
        model.calls().getLast());
  }

  @Test
  void theCompactionsSpendReachesTheRunWhenSkillsArePutBack() {
    var model = loadingPdf();

    var run =
        run(
            model,
            compacting(model, PDF)
                .withContextCompactor(
                    (history, context) ->
                        new CompactionResult(
                            List.of(history.getFirst(), history.get(1)),
                            Usage.of(700, 300),
                            "summarizer")));

    var usage = run.terminal().usage();
    assertEquals(List.of(700, 300), List.of(usage.inputTokens(), usage.outputTokens()));
    assertEquals(
        List.of(model.system(), tagged(PDF_LOADED), Message.user("go")), model.calls().getLast());
  }

  @Test
  void aCompactorThatChangesNothingGetsNothingPutBack() {
    var identity = loadingPdf();
    var disabled = loadingPdf();
    var answeringNull = loadingPdf();

    var unchanged =
        run(
            identity,
            compacting(identity, PDF)
                .withContextCompactor((history, context) -> CompactionResult.noOp(history)));
    var off =
        run(disabled, compacting(disabled, PDF).withContextCompactor(ContextCompactor.disabled()));
    var nothing =
        run(
            answeringNull,
            compacting(answeringNull, PDF).withContextCompactor((history, context) -> null));

    for (var model : List.of(identity, disabled, answeringNull)) {
      assertEquals(
          List.of(Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.TOOL),
          model.calls().getLast().stream().map(Message::role).toList());
    }
    assertEquals(
        List.of(List.of(), List.of(), List.of()),
        List.of(
            unchanged.events().eventsOf(QueryEvent.ContextEdited.class),
            off.events().eventsOf(QueryEvent.ContextEdited.class),
            nothing.events().eventsOf(QueryEvent.ContextEdited.class)));
  }

  @Test
  void aTaggedMessageWithoutContentIsNoActivation() {
    var model = loadingPdf();
    var empty =
        Message.newBuilder()
            .withRole(Role.USER)
            .withMetadata(MessageOrigin.SKILL.metadata())
            .build();

    run(
        model,
        compacting(model, PDF)
            .withContextCompactor(
                (history, context) ->
                    CompactionResult.noOp(List.of(history.getFirst(), empty, history.get(1)))));

    assertEquals(
        List.of(model.system(), tagged(PDF_LOADED), empty, Message.user("go")),
        model.calls().getLast());
  }

  @Test
  void skillsComeBackInFirstActivationOrderOnceAfterEveryCompaction() {
    var model =
        model(
            ScriptedModel.newBuilder()
                .withToolCallsTurn(load("c1", "forms"), load("c2", "pdf"))
                .withTextTurn("first gist")
                .withToolCallsTurn(echo("c3"))
                .withTextTurn("second gist")
                .withTextTurn("done"));

    run(model, compacting(model, PDF, FORMS));

    assertEquals(
        List.of(model.system(), tagged(FORMS_LOADED), tagged(PDF_LOADED), summary("first gist")),
        model.calls().get(2));
    assertEquals(
        List.of(model.system(), tagged(FORMS_LOADED), tagged(PDF_LOADED), summary("second gist")),
        model.calls().getLast());
  }

  @Test
  void aFileReadAndAFailedActivationAreNotPutBack() {
    var pdf =
        skill("pdf", "Extracts text from PDFs.", "Use pdfplumber.")
            .withFile("a.md", () -> "text")
            .build();
    var model =
        model(
            ScriptedModel.newBuilder()
                .withToolCallsTurn(load("c1", "pdf"), read("c2", "pdf", "a.md"), load("c3", "nope"))
                .withTextTurn("the gist")
                .withTextTurn("done"));

    run(model, compacting(model, pdf));

    var loaded =
        "<skill_content name=\"pdf\">\nUse pdfplumber.\n\n<skill_resources>\n<file>a.md</file>\n"
            + "</skill_resources>\n</skill_content>";
    assertEquals(
        List.of(model.system(), tagged(loaded), summary("the gist")), model.calls().getLast());
  }

  @Test
  void anotherToolsResultWithTheOpeningIsNotPutBack() {
    var model =
        model(
            ScriptedModel.newBuilder()
                .withToolCallsTurn(new ToolCall("c0", "forge", Map.of()))
                .withTextTurn("the gist")
                .withTextTurn("done"));

    run(model, compacting(model, PDF).withTool(tool("forge", PDF_LOADED)));

    assertEquals(List.of(model.system(), summary("the gist")), model.calls().getLast());
  }

  @Test
  void aMutateHistoryHookThatDropsTheActivationGetsNothingPutBack() {
    var model =
        model(
            ScriptedModel.newBuilder()
                .withToolCallsTurn(load("c1", "pdf"))
                .withToolCallsTurn(echo("c2"))
                .withTextTurn("the gist")
                .withTextTurn("done"));
    PreModelTurnHook droppingTheActivation =
        (history, context) ->
            context.turnIndex() == 2
                ? HookOutcome.mutateHistory(history.subList(0, 2))
                : HookOutcome.cont();

    run(
        model,
        compacting(model, PDF)
            .withTokenCounter(
                messages ->
                    messages.stream().anyMatch(m -> "echo".equals(m.toolName())) ? 96L : 10L)
            .withHook(droppingTheActivation));

    assertEquals(List.of(model.system(), summary("the gist")), model.calls().getLast());
  }

  // ── reopening ───────────────────────────────────────────────────────────

  /** A stored session in which the model loaded {@code pdf}, without compacting. */
  private static void loadedIn(SessionStore store) {
    var model = loadingPdf();
    run(model, reopened(model, store, PDF));
  }

  /**
   * Options over {@code store} that compact while the history holds an assistant turn and ends with
   * a user message starting {@code compact}.
   */
  private static SessionOptions.Builder reopened(
      Recording model, SessionStore store, Skill... skills) {
    return compacting(model, skills)
        .withStore(store)
        .withTokenCounter(
            messages ->
                messages.getLast().role() == Role.USER
                        && messages.getLast().content().startsWith("compact")
                        && messages.stream().anyMatch(m -> m.role() == Role.ASSISTANT)
                    ? 96L
                    : 10L);
  }

  @Test
  void aReopenedSessionGetsTheSkillBackFromItsStoredHistory() {
    var store = new InMemorySessionStore();
    loadedIn(store);
    var model = model(ScriptedModel.newBuilder().withTextTurn("the gist").withTextTurn("done"));

    var request = sent(model, reopened(model, store, PDF), "compact now");

    assertEquals(
        List.of(
            request.getFirst(),
            tagged(PDF_LOADED),
            summary("the gist"),
            Message.user("compact now")),
        request);
  }

  @Test
  void aSecondReopenGetsTheSkillBackFromTheTaggedMessageOnce() {
    var store = new RecordingStore();
    loadedIn(store);
    var first = model(ScriptedModel.newBuilder().withTextTurn("the gist").withTextTurn("done"));
    sent(first, reopened(first, store, PDF), "compact now");
    var second = model(ScriptedModel.newBuilder().withTextTurn("second gist").withTextTurn("done"));

    var request = sent(second, reopened(second, store, PDF), "compact again");

    var replaced =
        store.batches.stream()
            .flatMap(List::stream)
            .filter(SessionEntry.HistoryReplaced.class::isInstance)
            .map(entry -> ((SessionEntry.HistoryReplaced) entry).history())
            .toList();
    assertEquals(tagged(PDF_LOADED), replaced.getFirst().getFirst());
    assertEquals(
        List.of(
            request.getFirst(),
            tagged(PDF_LOADED),
            summary("second gist"),
            Message.user("compact again")),
        request);
  }

  @Test
  void aSkillRemovedFromTheOptionsIsNotPutBackAndCannotBeLoaded() {
    var store = new InMemorySessionStore();
    loadedIn(store);
    var model =
        model(
            ScriptedModel.newBuilder()
                .withTextTurn("the gist")
                .withToolCallsTurn(load("c2", "pdf"))
                .withTextTurn("done"));

    sent(model, reopened(model, store, FORMS), "compact now");

    var afterTheCompaction = model.calls().get(1);
    assertEquals(
        List.of(afterTheCompaction.getFirst(), summary("the gist"), Message.user("compact now")),
        afterTheCompaction);
    assertEquals(
        Message.tool("c2", "Skill", "Skill: no skill is named \"pdf\"; the skills are [forms]"),
        model.calls().get(2).getLast());
  }

  @Test
  void aReopenedSessionAnswersThatALoadedSkillIsLoaded() {
    var store = new InMemorySessionStore();
    loadedIn(store);
    var model =
        model(ScriptedModel.newBuilder().withToolCallsTurn(load("c2", "pdf")).withTextTurn("done"));

    var request = sent(model, reopened(model, store, PDF), "again");

    assertEquals(Message.tool("c2", "Skill", PDF_ALREADY_LOADED), request.getLast());
  }
}
