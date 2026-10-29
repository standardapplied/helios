/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session;

import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.Role;
import com.standardapplied.helios.session.hooks.HookContext;
import com.standardapplied.helios.session.loop.SessionHookContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Default {@link ContextCompactor}: replaces the whole conversation with one summary.
 *
 * <p>The compacted history is every leading {@code SYSTEM} message unchanged, one {@code USER}
 * message holding {@link #SUMMARY_PREFIX} and the summary, and the pending user turn (the {@code
 * USER} messages the history ends with, after its last assistant turn) unchanged. No earlier
 * assistant turn is carried over, so no reasoning a provider signed against the old conversation
 * (Claude Fable 5.1, Opus 5.5 and Sonnet 5.5 bind every thinking block to its exact request prefix)
 * is replayed, and no tool call or tool result remains: the result is valid for every provider.
 * Everything the agent knew before the summary survives only through it, so the default prompt asks
 * for the goal, the facts found, the decisions made, the open questions and the work left.
 *
 * <p>It never compacts in the middle of a tool round: while the last assistant turn with tool calls
 * has a call without a later {@code TOOL} answer, and when fewer than two messages would be
 * summarised, it returns {@link CompactionResult#noOp(List)} without calling the model.
 *
 * <p>The summary call runs on a virtual thread under a timeout (default {@value
 * #DEFAULT_SUMMARY_TIMEOUT_SECONDS}s). A call that throws, times out or returns a blank summary is
 * logged as a WARNING and leaves the history unchanged; the watermark fires again on the next
 * check. An {@link Error} the call ends with is returned as the result's {@link
 * CompactionResult#error() error}, which ends the session; called by the session, the compactor
 * first records it in the session's {@code Error} sink, so one thrown after the timeout still ends
 * the session. Every summary call reports the summary model's id on the {@link CompactionResult},
 * so the loop reports it as a model call: an answered call its usage, which the loop prices and
 * gates through {@code SessionLimits.maxBudgetMicroUsd}, and a failed or timed-out call its {@link
 * CompactionResult#error()} at zero usage.
 */
public final class SummarizingCompactor implements ContextCompactor {

  /**
   * Marker prepended to the summary message, so the next model turn recognises the row as a
   * compaction artefact rather than a user instruction.
   */
  public static final String SUMMARY_PREFIX = "[Earlier context summary]\n";

  private static final Logger LOG = Logger.getLogger(SummarizingCompactor.class.getName());

  private static final int DEFAULT_SUMMARY_TIMEOUT_SECONDS = 60;
  private static final int MIN_SUMMARISED_MESSAGES = 2;
  private static final String DEFAULT_SUMMARY_PROMPT =
      "You are a summariser. The following messages are a tool-using agent's conversation so far."
          + " The agent continues from your summary alone: every earlier message, its reasoning"
          + " included, is discarded. Produce a concise (under 300 words) summary that preserves:"
          + " (1) the user's goal, (2) the facts the agent has found, (3) the decisions made, (4)"
          + " the open questions, and (5) the work left to do. Omit redundant tool-call and"
          + " tool-result chatter. Plain text only.";

  private final Model summaryModel;
  private final String summaryPrompt;
  private final Duration summaryTimeout;

  private SummarizingCompactor(Builder b) {
    this.summaryModel = b.summaryModel;
    this.summaryPrompt = b.summaryPrompt;
    this.summaryTimeout = b.summaryTimeout;
  }

  /**
   * Start building a compactor.
   *
   * @param summaryModel the model used for the summarisation call; non-null
   * @return a fresh builder pre-populated with defaults
   * @throws NullPointerException if {@code summaryModel} is null
   */
  public static Builder newBuilder(Model summaryModel) {
    return new Builder(summaryModel);
  }

  @Override
  public CompactionResult compact(List<Message> history, HookContext context) {
    Objects.requireNonNull(history, "history must not be null");
    Objects.requireNonNull(context, "context must not be null");
    var summarisedFrom = leadingSystemMessages(history);
    var summarisedTo = pendingUserTurnStart(history);
    if (summarisedTo - summarisedFrom < MIN_SUMMARISED_MESSAGES || inToolRound(history)) {
      return CompactionResult.noOp(history);
    }
    var summarised = history.subList(summarisedFrom, summarisedTo);
    Optional<Response<Void>> answer;
    try {
      answer =
          invokeSummary(
              context, List.of(Message.system(summaryPrompt), Message.user(render(summarised))));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, e, () -> unchanged("summary call failed", context));
      return failed(history, e);
    } catch (Error e) {
      return failed(history, e);
    }
    if (answer.isEmpty()) {
      return failed(history, new TimeoutException("summary timed out after " + summaryTimeout));
    }
    var response = answer.get();
    var usage = response.usage() != null ? response.usage() : Usage.of(0, 0);
    if (Strings.isBlank(response.content())) {
      LOG.warning(() -> unchanged("summary returned blank", context));
      return new CompactionResult(history, usage, summaryModel.id(), null);
    }
    var compacted = new ArrayList<>(history.subList(0, summarisedFrom));
    compacted.add(Message.user(SUMMARY_PREFIX + response.content()));
    compacted.addAll(history.subList(summarisedTo, history.size()));
    return new CompactionResult(List.copyOf(compacted), usage, summaryModel.id(), null);
  }

  private CompactionResult failed(List<Message> history, Throwable error) {
    return new CompactionResult(history, Usage.of(0, 0), summaryModel.id(), error);
  }

  /**
   * Run the summary call on a virtual thread under the configured timeout. On timeout the worker
   * thread is interrupted (best effort) and this returns empty. What the call throws is rethrown as
   * itself, an {@link Error} included, an {@code Error} it carries as a joined future's cause as
   * that {@code Error}, and a checked exception it throws undeclared wrapped in a {@link
   * RuntimeException}; an {@code Error}, or one it carries as a joined future's cause, is first
   * recorded by the session's ending when {@code context} is the session's, so one thrown after the
   * timeout still ends the session.
   */
  private Optional<Response<Void>> invokeSummary(HookContext context, List<Message> request) {
    Consumer<Throwable> failures =
        context instanceof SessionHookContext session ? session.ending()::failWith : thrown -> {};
    var summary =
        BoundedTask.start(
            "helios-compaction-summary", () -> summaryModel.chat(request, List.of()), failures);
    if (summary.settlesBy(BoundedTask.deadlineAfter(summaryTimeout))) {
      return Optional.of(valueOf(summary.outcome()));
    }
    summary.interrupt();
    if (Thread.currentThread().isInterrupted()) {
      throw new RuntimeException(
          new InterruptedException("interrupted while waiting for the summary call"));
    }
    LOG.warning(() -> unchanged("summary timed out after " + summaryTimeout, context));
    return Optional.empty();
  }

  private static <T> T valueOf(CompletableFuture<T> settled) {
    if (settled.state() != Future.State.FAILED) {
      return settled.join();
    }
    switch (settled.exceptionNow()) {
      case Error error -> throw error;
      case ExecutionException wrapped when wrapped.getCause() instanceof Error error -> throw error;
      case RuntimeException re -> throw re;
      case Throwable thrown -> throw new RuntimeException(thrown);
    }
  }

  private static String unchanged(String reason, HookContext context) {
    return "Context compaction "
        + reason
        + "; leaving history unchanged. Session "
        + context.sessionId()
        + " turn "
        + context.turnIndex();
  }

  private static int leadingSystemMessages(List<Message> history) {
    var count = 0;
    while (count < history.size() && history.get(count).role() == Role.SYSTEM) {
      count++;
    }
    return count;
  }

  private static int pendingUserTurnStart(List<Message> history) {
    var start = history.size();
    while (start > 0 && history.get(start - 1).role() == Role.USER) {
      start--;
    }
    return start;
  }

  /** Whether the last assistant turn with tool calls has a call no later {@code TOOL} answers. */
  private static boolean inToolRound(List<Message> history) {
    var answered = new HashSet<String>();
    for (var i = history.size() - 1; i >= 0; i--) {
      var message = history.get(i);
      if (message.role() == Role.TOOL) {
        answered.add(message.toolCallId());
      } else if (message.role() == Role.ASSISTANT && message.hasToolCalls()) {
        return !message.toolCalls().stream().allMatch(call -> answered.contains(call.id()));
      }
    }
    return false;
  }

  private static String render(List<Message> messages) {
    var sb = new StringBuilder();
    for (var m : messages) {
      sb.append('[').append(m.role().name().toLowerCase(Locale.ROOT)).append("] ");
      if (m.content() != null) {
        sb.append(m.content());
      }
      if (m.hasToolCalls()) {
        for (var c : m.toolCalls()) {
          sb.append(" {tool_call ").append(c.name()).append('}');
        }
      }
      sb.append('\n');
    }
    return sb.toString();
  }

  /** Mutable builder for {@link SummarizingCompactor}. */
  public static final class Builder {

    private final Model summaryModel;
    private String summaryPrompt = DEFAULT_SUMMARY_PROMPT;
    private Duration summaryTimeout = Duration.ofSeconds(DEFAULT_SUMMARY_TIMEOUT_SECONDS);

    private Builder(Model summaryModel) {
      this.summaryModel = Objects.requireNonNull(summaryModel, "summaryModel must not be null");
    }

    /**
     * Override the summary prompt sent as a system message ahead of the rendered conversation. The
     * default asks for the user's goal, the facts found, the decisions made, the open questions and
     * the work left, since the agent continues from the summary alone.
     *
     * @param summaryPrompt non-blank prompt text
     * @return this builder
     * @throws NullPointerException if {@code summaryPrompt} is null
     * @throws IllegalArgumentException if {@code summaryPrompt} is blank
     */
    public Builder withSummaryPrompt(String summaryPrompt) {
      Objects.requireNonNull(summaryPrompt, "summaryPrompt must not be null");
      if (Strings.isBlank(summaryPrompt)) {
        throw new IllegalArgumentException("summaryPrompt must not be blank");
      }
      this.summaryPrompt = summaryPrompt;
      return this;
    }

    /**
     * Set the per-call timeout for the summary model call. Default {@value
     * #DEFAULT_SUMMARY_TIMEOUT_SECONDS} seconds. A hung summary call holds up the session loop for
     * this long.
     *
     * @param summaryTimeout non-null positive duration
     * @return this builder
     * @throws NullPointerException if {@code summaryTimeout} is null
     * @throws IllegalArgumentException if {@code summaryTimeout} is zero or negative
     */
    public Builder withSummaryTimeout(Duration summaryTimeout) {
      Objects.requireNonNull(summaryTimeout, "summaryTimeout must not be null");
      if (summaryTimeout.isZero() || summaryTimeout.isNegative()) {
        throw new IllegalArgumentException(
            "summaryTimeout must be positive, got " + summaryTimeout);
      }
      this.summaryTimeout = summaryTimeout;
      return this;
    }

    /**
     * Build the immutable compactor.
     *
     * @return the compactor
     */
    public SummarizingCompactor build() {
      return new SummarizingCompactor(this);
    }
  }
}
