/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.examples.session;

import com.standardapplied.helios.anthropic.AnthropicPricing;
import com.standardapplied.helios.anthropic.CachePolicy;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.Role;
import com.standardapplied.helios.core.tool.ParameterType;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolParameter;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.ContextCompactor;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.session.SummarizingCompactor;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.test.CollectingSubscriber;
import com.standardapplied.helios.session.test.QuestionAnswers;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolRegistry;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The Claude session the live and recorded compaction tests run: one lookup tool, the session's
 * default compactor, and a token count that crosses the compaction watermark once, after the first
 * tool round. Two turns at most, so the run stops after the first request that follows the
 * compaction.
 */
final class CompactingSession {

  /**
   * The metadata key under which the Anthropic provider records a turn's signed thinking blocks.
   */
  static final String THINKING_BLOCKS_KEY = "anthropic.thinkingBlocks";

  /** What one run produced: its terminal, its events and the history it compacted, if any. */
  record Run(
      ResultMessage terminal, CollectingSubscriber events, Optional<List<Message>> compacted) {

    /** Whether the first assistant turn of the compacted history held a signed thinking block. */
    boolean compactedASignedThinkingTurn() {
      return compacted.stream()
          .flatMap(List::stream)
          .filter(message -> message.role() == Role.ASSISTANT)
          .findFirst()
          .map(message -> message.metadata().containsKey(THINKING_BLOCKS_KEY))
          .orElse(false);
    }
  }

  private CompactingSession() {}

  /** Run the session over {@code model} to its terminal. */
  static Run run(Model model) {
    var compacted = new AtomicReference<List<Message>>();
    var defaultCompactor = SummarizingCompactor.newBuilder(model).build();
    ContextCompactor recording =
        (history, context) -> {
          var result = defaultCompactor.compact(history, context);
          if (result.history().size() < history.size()) {
            compacted.set(history);
          }
          return result;
        };
    var options =
        SessionOptions.newBuilder()
            .withModel(model)
            .withSystemPrompt("Answer questions about members by looking them up with get_profile.")
            .withTools(new ToolRegistry(List.of(getProfile())))
            .withCostCalculator(AnthropicPricing.calculator(CachePolicy.shortLived()))
            .withTokenCounter(CompactingSession::tokens)
            .withLimits(
                SessionLimits.newBuilder().withMaxContextTokens(100L).withMaxTurns(2).build())
            .withContextCompactor(recording)
            .build();
    try (var session = AgentSession.create(options)) {
      var events = new CollectingSubscriber(QuestionAnswers.selecting(session, "Deny"));
      session.events().subscribe(events);
      var terminal =
          session.runBlocking(
              UserMessage.text(
                  "A member's id is p followed by the sum of the primes below 30. Work out the"
                      + " id, look that member up with get_profile, then tell me in one sentence"
                      + " who they are."));
      events.awaitDone();
      return new Run(terminal, events, Optional.ofNullable(compacted.get()));
    }
  }

  /** Over the watermark from the first tool result on, until the history holds a summary. */
  private static long tokens(List<Message> history) {
    var summarised =
        history.stream()
            .anyMatch(
                m ->
                    m.content() != null
                        && m.content().startsWith(SummarizingCompactor.SUMMARY_PREFIX));
    var toolRound = history.stream().anyMatch(m -> m.role() == Role.TOOL);
    return toolRound && !summarised ? 96L : 10L;
  }

  private static ToolBinding getProfile() {
    var tool =
        Tool.newBuilder()
            .withName("get_profile")
            .withDescription("Read one member profile by id")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("id")
                    .withType(ParameterType.STRING)
                    .withDescription("the member id")
                    .withRequired(true)
                    .build())
            .withIdempotent(true)
            .withExecutor(
                (args, ctx) ->
                    ToolResult.success(
                        "Operator turned angel investor, hardware supply chain background, based"
                            + " in Texas."))
            .build();
    return ToolBinding.newBuilder(tool).withCategory(ToolCategory.SEARCH).build();
  }
}
