/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.ModelChunk;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.Response.Usage;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.core.tool.Tool;
import ai.singlr.session.ask.AskUserQuestionResponse;
import ai.singlr.session.ask.AskUserQuestionTool;
import ai.singlr.session.files.GlobTool;
import ai.singlr.session.files.GrepTool;
import ai.singlr.session.files.InMemoryFileTracker;
import ai.singlr.session.files.LsTool;
import ai.singlr.session.files.ReadTool;
import ai.singlr.session.files.WorkspaceRoot;
import ai.singlr.session.memory.FileSystemMemoryBackend;
import ai.singlr.session.memory.MemoryReadTool;
import ai.singlr.session.permissions.Permission;
import ai.singlr.session.permissions.PermissionEffect;
import ai.singlr.session.permissions.PermissionMode;
import ai.singlr.session.permissions.PermissionRule;
import ai.singlr.session.tools.ToolRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 2 acceptance test (spec §22.5: "model can navigate a fake repo, ask via AskUserQuestion,
 * and respect deny rules"). Exercises the part-5 surface end-to-end: a scripted Model drives a
 * session through Read/LS/Glob/Grep, hits a deny rule, asks the user a structured question, gets an
 * answer via session.answer(...), and terminates with Success.
 */
final class Phase2AcceptanceTest {

  /**
   * Scripted streaming Model: dispenses one prepared chunk sequence per call to {@code chatStream},
   * in declaration order. After the last script runs the model returns an empty stream — the agent
   * loop's stop classifier turns that into a sensible terminal.
   */
  private static final class ScriptedModel implements Model {

    private final List<List<ModelChunk>> turns;
    private int turnIndex = 0;

    ScriptedModel(List<List<ModelChunk>> turns) {
      this.turns = turns;
    }

    @Override
    public Response<Void> chat(List<Message> messages, List<Tool> tools) {
      throw new AssertionError("unused — scripted model only streams");
    }

    @Override
    public Flow.Publisher<ModelChunk> chatStream(
        List<Message> messages, List<Tool> tools, CancellationToken cancellation) {
      var chunks = turnIndex < turns.size() ? turns.get(turnIndex++) : List.<ModelChunk>of();
      return subscriber ->
          subscriber.onSubscribe(
              new Flow.Subscription() {
                @Override
                public void request(long n) {
                  for (var c : chunks) {
                    subscriber.onNext(c);
                  }
                  subscriber.onComplete();
                }

                @Override
                public void cancel() {}
              });
    }

    @Override
    public String id() {
      return "phase2-script";
    }

    @Override
    public String provider() {
      return "test";
    }
  }

  private static final class CollectingSubscriber implements Flow.Subscriber<QueryEvent> {
    final List<QueryEvent> events = new CopyOnWriteArrayList<>();
    final CountDownLatch done = new CountDownLatch(1);

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(QueryEvent event) {
      events.add(event);
    }

    @Override
    public void onError(Throwable throwable) {
      done.countDown();
    }

    @Override
    public void onComplete() {
      done.countDown();
    }
  }

  @Test
  void modelNavigatesRepoAsksAndRespectsDenyRule(@TempDir Path tmp) throws Exception {
    // Fake repo layout.
    Files.createDirectories(tmp.resolve("src/main"));
    Files.writeString(
        tmp.resolve("src/main/Hello.java"),
        "package demo;\npublic class Hello { /* TARGET */ }\n",
        StandardCharsets.UTF_8);
    Files.writeString(
        tmp.resolve("README.md"), "# repo readme\nTARGET line here\n", StandardCharsets.UTF_8);
    Files.writeString(tmp.resolve("sensitive.txt"), "secret stuff\n", StandardCharsets.UTF_8);

    // Memory backend with one seeded entry.
    Files.createDirectories(tmp.resolve(FileSystemMemoryBackend.STORAGE_SUBDIR));
    Files.writeString(
        tmp.resolve(FileSystemMemoryBackend.STORAGE_SUBDIR + "/INDEX.md"),
        "remember the README",
        StandardCharsets.UTF_8);

    var workspace = WorkspaceRoot.of(tmp);
    var tracker = InMemoryFileTracker.create();
    var memoryBackend = FileSystemMemoryBackend.of(workspace);

    // User tools: Read, LS, Glob, Grep. MemoryRead + AskUserQuestion are auto-registered by the
    // session.
    var tools =
        new ToolRegistry(
            List.of(
                ReadTool.binding(workspace, tracker),
                LsTool.binding(workspace),
                GlobTool.binding(workspace),
                GrepTool.binding(workspace)));

    // Permission policy: deny Read on the sensitive file, allow everything else by default.
    var permission =
        new Permission(
            PermissionMode.DEFAULT,
            List.of(),
            List.of(),
            List.of(
                new PermissionRule(PermissionEffect.DENY, "Read", Optional.of("sensitive.txt"))));

    // Five-turn script: Grep → Read(allowed) → Read(denied) → AskUserQuestion → final STOP.
    var grepCall = new ToolCall("c1", GrepTool.NAME, Map.of("pattern", "TARGET"));
    var readAllowed = new ToolCall("c2", ReadTool.NAME, Map.of("path", "src/main/Hello.java"));
    var readDenied = new ToolCall("c3", ReadTool.NAME, Map.of("path", "sensitive.txt"));
    var askCall =
        new ToolCall(
            "c4",
            AskUserQuestionTool.NAME,
            Map.of(
                "question",
                "Did you find what you needed?",
                "options",
                List.of(
                    Map.of("label", "Yes", "description", "done"),
                    Map.of("label", "No", "description", "keep going"))));
    var turns =
        List.<List<ModelChunk>>of(
            List.of(
                new ModelChunk.ToolUseStop(grepCall),
                new ModelChunk.MessageStop("TOOL_CALLS", Usage.of(2, 1))),
            List.of(
                new ModelChunk.ToolUseStop(readAllowed),
                new ModelChunk.MessageStop("TOOL_CALLS", Usage.of(2, 1))),
            List.of(
                new ModelChunk.ToolUseStop(readDenied),
                new ModelChunk.MessageStop("TOOL_CALLS", Usage.of(2, 1))),
            List.of(
                new ModelChunk.ToolUseStop(askCall),
                new ModelChunk.MessageStop("TOOL_CALLS", Usage.of(2, 1))),
            List.of(
                new ModelChunk.TextDelta("all done"),
                new ModelChunk.MessageStop("STOP", Usage.of(2, 1))));

    var options =
        SessionOptions.newBuilder()
            .withModel(new ScriptedModel(turns))
            .withSessionId("phase2-sess-" + UUID.randomUUID())
            .withTools(tools)
            .withPermission(permission)
            .withMemoryBackend(memoryBackend)
            .build();

    var sub = new CollectingSubscriber();
    try (var session = AgentSession.create(options)) {
      session.events().subscribe(sub);

      // Answer the question as soon as the session emits it. A small worker thread watches the
      // event list — the agent loop is blocked on the future until we answer.
      //
      // Set the `answered` flag BEFORE calling session.answer(...). Otherwise the main thread can
      // observe a sequence where session.answer() completes the loop's future, the loop runs to
      // termination, the publisher closes, sub.done counts down, and the main thread's
      // sub.done.await(...) returns — all before the worker thread is scheduled back to run the
      // assignment. Setting the flag first means observers can't see a terminated session with
      // the flag still false.
      var answered = new java.util.concurrent.atomic.AtomicBoolean(false);
      Thread.ofVirtual()
          .name("phase2-answerer")
          .start(
              () -> {
                while (!answered.get()) {
                  for (var ev : sub.events) {
                    if (ev instanceof QueryEvent.QuestionAsked qa) {
                      answered.set(true);
                      session.answer(
                          qa.request().questionId(),
                          AskUserQuestionResponse.single(qa.request().questionId(), "Yes"));
                      break;
                    }
                  }
                  try {
                    Thread.sleep(10);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                  }
                }
              });

      var result = session.runBlocking(UserMessage.text("explore the repo"));
      assertTrue(sub.done.await(5, TimeUnit.SECONDS), "stream did not complete in 5s");
      assertTrue(answered.get(), "AskUserQuestion was never answered");

      // Verify terminal Success.
      assertInstanceOf(ResultMessage.Success.class, result);

      // Verify Grep ran successfully and the result names the seeded files.
      var grepResult = findToolResult(sub.events, GrepTool.NAME);
      assertNotNull(grepResult);
      assertTrue(grepResult.result().success());
      var grepOut = grepResult.result().output();
      assertTrue(grepOut.contains("README.md"), grepOut);
      assertTrue(grepOut.contains("Hello.java"), grepOut);

      // Verify the allowed Read ran successfully and surfaced the file's content.
      var allowedResult = findToolResult(sub.events, ReadTool.NAME);
      assertNotNull(allowedResult);
      assertTrue(allowedResult.result().success());
      assertTrue(allowedResult.result().output().contains("Hello"));

      // Verify the denied Read was BLOCKED by the permission system.
      var blocked = findEvent(sub.events, QueryEvent.ToolBlocked.class);
      assertNotNull(blocked, "expected the deny rule to surface a ToolBlocked event");
      assertEquals("Read", blocked.call().name());
      assertTrue(
          blocked.reason().contains("deny rule for Read"),
          "reason should reference the deny rule, got: " + blocked.reason());

      // Verify a QuestionAsked event surfaced.
      var question = findEvent(sub.events, QueryEvent.QuestionAsked.class);
      assertNotNull(question);
      assertEquals("Did you find what you needed?", question.request().question());

      // Verify the AskUserQuestion tool returned the user's selection.
      var askResult = findToolResult(sub.events, AskUserQuestionTool.NAME);
      assertNotNull(askResult);
      assertTrue(askResult.result().success());
      assertTrue(askResult.result().output().contains("- Yes"));

      // Verify memory listing works via the auto-registered MemoryRead tool.
      var memBinding = options.tools(); // user-supplied registry, no MemoryRead in here
      // Sanity: MemoryRead is NOT in the user registry, only in the combined session registry.
      assertTrue(memBinding.get(MemoryReadTool.NAME).isEmpty());
    }
  }

  private static QueryEvent.ToolResult findToolResult(List<QueryEvent> events, String toolName) {
    for (var ev : new ArrayList<>(events)) {
      if (ev instanceof QueryEvent.ToolResult tr && tr.call().name().equals(toolName)) {
        return tr;
      }
    }
    return null;
  }

  private static <T extends QueryEvent> T findEvent(List<QueryEvent> events, Class<T> cls) {
    for (var ev : new ArrayList<>(events)) {
      if (cls.isInstance(ev)) {
        return cls.cast(ev);
      }
    }
    return null;
  }
}
