/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.examples.session;

import com.standardapplied.helios.anthropic.AnthropicModelId;
import com.standardapplied.helios.anthropic.AnthropicProvider;
import com.standardapplied.helios.core.common.CostCalculator;
import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.ContextCompactor;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.session.SessionPresets;
import com.standardapplied.helios.session.SummarizingCompactor;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.PreToolUseHook;
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.permissions.PermissionMode;
import com.standardapplied.helios.session.permissions.PermissionRule;
import com.standardapplied.helios.session.store.FileSessionStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The README quick starts that call {@code SessionPresets}, one method per block, so the build
 * compiles every one of them. A block's free variables are its method's parameters. {@link
 * QuickStartsDocsTest} holds each README block to its method's body; none of these methods runs.
 */
final class QuickStarts {

  /** Where {@link #archivingCompaction} keeps the history on both sides of a compaction. */
  interface CompactionArchive {

    void save(List<Message> history);

    void saveCompacted(List<Message> history);
  }

  private QuickStarts() {}

  static void workspaceSession(Model model) {
    try (var session =
        AgentSession.open(SessionPresets.workspace(model, Path.of("/path/to/repo")).build())) {
      var terminal =
          session.runBlocking(UserMessage.text("Summarise the public API of the session module."));
      System.out.println(((ResultMessage.Success) terminal).result());
    }
  }

  static void chat(Model model, Path repo) {
    try (var session = AgentSession.open(SessionPresets.workspace(model, repo).build())) {
      var answer = session.send("Which classes form the session's public API?").join();
      var followUp = session.send("Which of them does a chat application need?").join();
      System.out.println(answer + "\n" + followUp);
    }
  }

  static void storedChat(SessionOptions options) {
    var store = FileSessionStore.of(Path.of("conversations"));
    try (var session =
        AgentSession.open(
            options.toBuilder().withSessionId("member-42").withStore(store).build())) {
      session.send("Where did we leave off?").join();
    }
  }

  static void sharedModel(String modelId, ModelConfig config, List<String> requests, Path repo) {
    try (var model = new AnthropicProvider().create(modelId, config)) {
      for (var request : requests) {
        try (var session = AgentSession.open(SessionPresets.workspace(model, repo).build())) {
          session.runBlocking(UserMessage.text(request));
        }
      }
    } // HttpClient and connection pool released here
  }

  static void minimalSession() {
    var model =
        new AnthropicProvider()
            .create(
                AnthropicModelId.CLAUDE_SONNET_4_6.id(),
                ModelConfig.of(System.getenv("ANTHROPIC_API_KEY")));

    try (var session = AgentSession.open(SessionPresets.minimal(model).build())) {
      var terminal = session.runBlocking(UserMessage.text("What is the capital of France?"));
      switch (terminal) {
        case ResultMessage.Success s -> System.out.println(s.result());
        case ResultMessage other -> System.err.println("non-Success: " + other);
      }
    }
  }

  static void budgetCap(Model model, Path root) {
    var pricing = Map.of("claude-sonnet-4-6", CostCalculator.Pricing.ofUsdPerMillion(3.0, 15.0));
    var limits =
        SessionLimits.newBuilder()
            .withMaxBudgetMicroUsd(5 * CostEstimate.MICRO_USD_PER_USD) // $5
            .build();

    try (var session =
        AgentSession.open(
            SessionPresets.workspace(model, root)
                .withCostCalculator(CostCalculator.staticTable(pricing))
                .withLimits(limits)
                .build())) {
      var terminal = session.runBlocking(UserMessage.text("..."));
      if (terminal instanceof ResultMessage.ErrorMaxBudgetUsd b) {
        System.out.println("Budget exceeded at " + (b.microUsdSpent() / 1_000_000.0) + " USD");
      }
    }
  }

  static void permissions(Model model, Path root) {
    var policy =
        new Permission(
            PermissionMode.DEFAULT,
            List.of(PermissionRule.of("Read", "./src/**")),
            List.of(PermissionRule.of("MemoryWrite", "/memories/**")),
            List.of(PermissionRule.of("Read", "**/secrets.*")));

    try (var session =
        AgentSession.open(SessionPresets.workspace(model, root).withPermission(policy).build())) {
      session.runBlocking(UserMessage.text("Summarise the public API under src."));
    }
  }

  static void hooks(Model model, Path root) {
    PreToolUseHook denyShell =
        (call, ctx) -> {
          if (call.name().equals("Execute")) {
            return HookOutcome.block("Execute disabled in this deployment");
          }
          return HookOutcome.cont();
        };

    var session =
        AgentSession.open(SessionPresets.workspace(model, root).withHook(denyShell).build());
  }

  static void archivingCompaction(
      Model model, Path repo, SummarizingCompactor defaultCompactor, CompactionArchive archive) {
    ContextCompactor archiving =
        (history, context) -> {
          archive.save(history);
          var result = defaultCompactor.compact(history, context);
          archive.saveCompacted(result.history());
          return result;
        };
    var options = SessionPresets.workspace(model, repo).withContextCompactor(archiving).build();
  }

  static void compaction(Model model, Path repo, Model cheapHaikuModel) {
    // 1) Default — SummarizingCompactor bound to the session's model
    var session = AgentSession.open(SessionPresets.workspace(model, repo).build());

    // 2) BYO compactor — a cheaper summary model or prompt, or any ContextCompactor of your own
    var custom =
        SummarizingCompactor.newBuilder(cheapHaikuModel)
            .withSummaryPrompt("Summarise focusing on file edits and unresolved questions.")
            .withSummaryTimeout(Duration.ofSeconds(30)) // bound the summary call
            .build();
    var session2 =
        AgentSession.open(
            SessionPresets.workspace(model, repo).withContextCompactor(custom).build());

    // 3) Opt out wholesale — long sessions overflow naturally with an ErrorDuringExecution
    var session3 =
        AgentSession.open(
            SessionPresets.workspace(model, repo)
                .withContextCompactor(ContextCompactor.disabled())
                .build());
  }
}
