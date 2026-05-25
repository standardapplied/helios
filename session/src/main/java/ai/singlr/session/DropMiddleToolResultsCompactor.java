/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import ai.singlr.core.common.Strings;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.Response.Usage;
import ai.singlr.core.model.Role;
import ai.singlr.session.loop.SessionState;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Default {@link ContextCompactor}: preserve the head, preserve the tail, summarise the middle.
 *
 * <p>Shape:
 *
 * <ol>
 *   <li>Keep the first {@link Builder#withHeadPreserved head N} messages — typically the system
 *       prompt plus the opening user turn, the rows the agent needs forever.
 *   <li>Keep the last {@link Builder#withTailPreserved tail M} messages — the recent trajectory the
 *       model needs to continue coherently.
 *   <li>Replace everything in between with a single user-role summary message produced by one call
 *       to {@link #summaryModel()} against the same provider. The summary is prefixed with {@code
 *       "[Earlier context summary]"} so the next turn's model knows the row is a compaction
 *       artefact rather than a real user instruction.
 * </ol>
 *
 * <h2>Tool-call boundary alignment</h2>
 *
 * <p>Naive slicing would split assistant {@code tool_call} messages from their matching {@code
 * TOOL} responses across the head/middle or middle/tail boundary, producing a history every
 * provider rejects ({@code tool_use without matching tool_result}). This compactor walks the head
 * boundary forward and the tail boundary backward until each slice is self-consistent (a complete
 * prefix / complete suffix wrt pending tool-call ids). When no safe cut exists at or past the
 * requested boundaries — i.e. the history is one giant tool-call sequence — the compactor returns
 * the original history unchanged via {@link CompactionResult#noOp(List)}.
 *
 * <h2>Summary call resilience</h2>
 *
 * <p>The summary model call runs on a virtual thread under a configurable timeout (default {@value
 * #DEFAULT_SUMMARY_TIMEOUT_SECONDS}s). If the call throws, times out, or returns blank, the
 * compactor logs a WARNING and returns the original history. Compaction failure must not crash the
 * session — the watermark will fire again on the next turn and the library user can switch to a
 * stricter compactor.
 *
 * <p>Successful calls report the summary call's {@code Usage} on the returned {@link
 * CompactionResult} so the agent loop can accumulate it into session totals + apply the configured
 * {@code CostCalculator}. Without this, compaction spend would be invisible to {@code
 * SessionLimits.maxBudgetMicroUsd} gating.
 */
public final class DropMiddleToolResultsCompactor implements ContextCompactor {

  private static final Logger LOG =
      Logger.getLogger(DropMiddleToolResultsCompactor.class.getName());

  private static final int DEFAULT_HEAD_PRESERVED = 3;
  private static final int DEFAULT_TAIL_PRESERVED = 20;
  private static final int DEFAULT_SUMMARY_TIMEOUT_SECONDS = 60;
  private static final String DEFAULT_SUMMARY_PROMPT =
      "You are a summariser. The following messages are earlier turns of a tool-using agent's "
          + "conversation. Produce a concise (under 200 words) summary that preserves: (1) the "
          + "user's goal, (2) key facts the agent has discovered, (3) any open questions or "
          + "decisions made. Omit redundant tool-call/result chatter. Plain text only.";

  /**
   * Marker prepended to the synthesised summary user-message so downstream consumers — most notably
   * {@link ai.singlr.session.hooks.CompactionPayload#summary() CompactionPayload.summary()} and the
   * next-turn model itself — can recognise that the row stands in for a compacted-away middle.
   * Public to give the {@code PostCompactHook} payload one source of truth for the marker rather
   * than relying on a duplicated string literal.
   */
  public static final String SUMMARY_PREFIX = "[Earlier context summary]\n";

  private final Model summaryModel;
  private final int headPreserved;
  private final int tailPreserved;
  private final String summaryPrompt;
  private final Duration summaryTimeout;

  private DropMiddleToolResultsCompactor(Builder b) {
    this.summaryModel = b.summaryModel;
    this.headPreserved = b.headPreserved;
    this.tailPreserved = b.tailPreserved;
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

  /**
   * The model used for the summarisation call.
   *
   * @return non-null model
   */
  public Model summaryModel() {
    return summaryModel;
  }

  /**
   * The number of head messages preserved across compaction (before boundary alignment).
   *
   * @return positive count
   */
  public int headPreserved() {
    return headPreserved;
  }

  /**
   * The number of tail messages preserved across compaction (before boundary alignment).
   *
   * @return positive count
   */
  public int tailPreserved() {
    return tailPreserved;
  }

  /**
   * The per-call timeout applied to the summarisation model call.
   *
   * @return non-null positive duration
   */
  public Duration summaryTimeout() {
    return summaryTimeout;
  }

  @Override
  public CompactionResult compact(List<Message> history, SessionState state) {
    Objects.requireNonNull(history, "history must not be null");
    Objects.requireNonNull(state, "state must not be null");

    if (history.size() <= headPreserved + tailPreserved) {
      return CompactionResult.noOp(history);
    }

    var headCut = adjustHead(history, headPreserved);
    var tailStart = adjustTailStart(history, history.size() - tailPreserved);
    if (headCut < 0 || tailStart < 0 || headCut >= tailStart) {
      return CompactionResult.noOp(history);
    }

    var head = history.subList(0, headCut);
    var middle = history.subList(headCut, tailStart);
    var tail = history.subList(tailStart, history.size());

    String summary;
    Usage usage;
    try {
      var request = new ArrayList<Message>(2);
      request.add(Message.system(summaryPrompt));
      request.add(Message.user(renderMiddle(middle)));
      var response = invokeSummary(state, request);
      if (response == null) {
        return CompactionResult.noOp(history);
      }
      summary = response.content();
      usage = response.usage() != null ? response.usage() : Usage.of(0, 0);
    } catch (RuntimeException e) {
      LOG.log(
          Level.WARNING,
          e,
          () ->
              "Context compaction summary call failed; leaving history unchanged. Session "
                  + state.sessionId()
                  + " turn "
                  + state.currentTurnIndex());
      return CompactionResult.noOp(history);
    }

    if (Strings.isBlank(summary)) {
      LOG.warning(
          () ->
              "Context compaction summary returned blank; leaving history unchanged. Session "
                  + state.sessionId()
                  + " turn "
                  + state.currentTurnIndex());
      return CompactionResult.noOp(history);
    }

    var summaryMessage = Message.user(SUMMARY_PREFIX + summary);
    var newHistory = new ArrayList<Message>(head.size() + 1 + tail.size());
    newHistory.addAll(head);
    newHistory.add(summaryMessage);
    newHistory.addAll(tail);
    return new CompactionResult(List.copyOf(newHistory), usage, summaryModel.id());
  }

  /**
   * Run the summary call on a virtual thread under the configured timeout. On timeout the worker
   * thread is interrupted (best effort) and this returns null so the caller falls back to no-op
   * compaction.
   */
  private Response<Void> invokeSummary(SessionState state, List<Message> request) {
    var future = new CompletableFuture<Response<Void>>();
    var worker =
        Thread.ofVirtual()
            .name("helios-compaction-summary")
            .start(
                () -> {
                  try {
                    future.complete(summaryModel.chat(List.copyOf(request)));
                  } catch (Throwable t) {
                    future.completeExceptionally(t);
                  }
                });
    try {
      return future.get(summaryTimeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      worker.interrupt();
      LOG.warning(
          () ->
              "Context compaction summary timed out after "
                  + summaryTimeout
                  + "; leaving history unchanged. Session "
                  + state.sessionId()
                  + " turn "
                  + state.currentTurnIndex());
      return null;
    } catch (ExecutionException e) {
      if (e.getCause() instanceof RuntimeException re) {
        throw re;
      }
      throw new RuntimeException(e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      worker.interrupt();
      throw new RuntimeException(e);
    }
  }

  /**
   * Walk forward from {@code proposed} to the smallest {@code k >= proposed} where messages {@code
   * [0..k)} contain no pending (unmatched) tool-call ids. Returns {@code -1} when no safe boundary
   * exists in the remaining history.
   */
  static int adjustHead(List<Message> history, int proposed) {
    var pending = new HashSet<String>();
    for (var k = 0; k <= history.size(); k++) {
      if (k >= proposed && pending.isEmpty()) {
        return k;
      }
      if (k == history.size()) {
        break;
      }
      var m = history.get(k);
      if (m.role() == Role.ASSISTANT && m.hasToolCalls()) {
        for (var c : m.toolCalls()) {
          if (c.id() != null) {
            pending.add(c.id());
          }
        }
      } else if (m.role() == Role.TOOL && m.toolCallId() != null) {
        pending.remove(m.toolCallId());
      }
    }
    return -1;
  }

  /**
   * Walk backward from {@code proposed} to the largest {@code j <= proposed} where messages {@code
   * [j..n)} contain no orphan tool responses (no TOOL message whose tool_call id is at index {@code
   * < j}). Returns {@code -1} when no safe boundary exists.
   */
  static int adjustTailStart(List<Message> history, int proposed) {
    var orphans = new HashSet<String>();
    var j = history.size();
    while (j > 0) {
      j--;
      var m = history.get(j);
      if (m.role() == Role.TOOL && m.toolCallId() != null) {
        orphans.add(m.toolCallId());
      } else if (m.role() == Role.ASSISTANT && m.hasToolCalls()) {
        for (var c : m.toolCalls()) {
          if (c.id() != null) {
            orphans.remove(c.id());
          }
        }
      }
      if (j <= proposed && orphans.isEmpty()) {
        return j;
      }
    }
    return orphans.isEmpty() ? 0 : -1;
  }

  private static String renderMiddle(List<Message> middle) {
    var sb = new StringBuilder();
    for (var m : middle) {
      sb.append('[').append(m.role().name().toLowerCase(Locale.ROOT)).append("] ");
      if (m.content() != null) {
        sb.append(m.content());
      }
      if (m.hasToolCalls()) {
        for (var c : m.toolCalls()) {
          sb.append(" {tool_call ").append(c.name()).append("}");
        }
      }
      sb.append('\n');
    }
    return sb.toString();
  }

  /** Mutable builder for {@link DropMiddleToolResultsCompactor}. */
  public static final class Builder {

    private final Model summaryModel;
    private int headPreserved = DEFAULT_HEAD_PRESERVED;
    private int tailPreserved = DEFAULT_TAIL_PRESERVED;
    private String summaryPrompt = DEFAULT_SUMMARY_PROMPT;
    private Duration summaryTimeout = Duration.ofSeconds(DEFAULT_SUMMARY_TIMEOUT_SECONDS);

    private Builder(Model summaryModel) {
      this.summaryModel = Objects.requireNonNull(summaryModel, "summaryModel must not be null");
    }

    /**
     * Set the number of head messages preserved (before boundary alignment may extend it forward to
     * keep tool-call pairs intact). Default {@value #DEFAULT_HEAD_PRESERVED}.
     *
     * @param headPreserved positive count
     * @return this builder
     * @throws IllegalArgumentException if {@code headPreserved < 1}
     */
    public Builder withHeadPreserved(int headPreserved) {
      if (headPreserved < 1) {
        throw new IllegalArgumentException("headPreserved must be >= 1, got " + headPreserved);
      }
      this.headPreserved = headPreserved;
      return this;
    }

    /**
     * Set the number of tail messages preserved (before boundary alignment may shift it backward to
     * keep tool-call pairs intact). Default {@value #DEFAULT_TAIL_PRESERVED}.
     *
     * @param tailPreserved positive count
     * @return this builder
     * @throws IllegalArgumentException if {@code tailPreserved < 1}
     */
    public Builder withTailPreserved(int tailPreserved) {
      if (tailPreserved < 1) {
        throw new IllegalArgumentException("tailPreserved must be >= 1, got " + tailPreserved);
      }
      this.tailPreserved = tailPreserved;
      return this;
    }

    /**
     * Override the summary prompt sent as a system message ahead of the rendered middle. Default is
     * a generic "summarise the goal, facts, and decisions" prompt. Pass a stricter prompt for
     * regulated workflows that need traceability hints in the summary.
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
     * #DEFAULT_SUMMARY_TIMEOUT_SECONDS} seconds. Bound this aggressively for latency-sensitive
     * deployments — a hung summary call would otherwise block the entire session loop.
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
    public DropMiddleToolResultsCompactor build() {
      return new DropMiddleToolResultsCompactor(this);
    }
  }
}
