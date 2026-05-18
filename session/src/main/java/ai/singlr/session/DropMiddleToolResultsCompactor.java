/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import ai.singlr.core.common.Strings;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.session.loop.SessionState;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
 * <p>When the history's length is {@code <= head + tail}, the compactor returns the history
 * unchanged — the loop interprets that as "no-op" and does not emit {@code ContextEdited}.
 *
 * <p>If the summary model throws or returns blank, the compactor logs a WARNING and returns the
 * original history. Compaction failure must not crash the session — the watermark will fire again
 * next turn and the library user can switch to a stricter compactor.
 *
 * <p>Simpler than the v1 4-phase compactor: no protected-tail tokenisation, no iterative carryover,
 * just first/middle/last with one summary call.
 */
public final class DropMiddleToolResultsCompactor implements ContextCompactor {

  private static final Logger LOG =
      Logger.getLogger(DropMiddleToolResultsCompactor.class.getName());

  private static final int DEFAULT_HEAD_PRESERVED = 3;
  private static final int DEFAULT_TAIL_PRESERVED = 20;
  private static final String DEFAULT_SUMMARY_PROMPT =
      "You are a summariser. The following messages are earlier turns of a tool-using agent's "
          + "conversation. Produce a concise (under 200 words) summary that preserves: (1) the "
          + "user's goal, (2) key facts the agent has discovered, (3) any open questions or "
          + "decisions made. Omit redundant tool-call/result chatter. Plain text only.";
  private static final String SUMMARY_PREFIX = "[Earlier context summary]\n";

  private final Model summaryModel;
  private final int headPreserved;
  private final int tailPreserved;
  private final String summaryPrompt;

  private DropMiddleToolResultsCompactor(Builder b) {
    this.summaryModel = b.summaryModel;
    this.headPreserved = b.headPreserved;
    this.tailPreserved = b.tailPreserved;
    this.summaryPrompt = b.summaryPrompt;
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
   * The number of head messages preserved across compaction.
   *
   * @return positive count
   */
  public int headPreserved() {
    return headPreserved;
  }

  /**
   * The number of tail messages preserved across compaction.
   *
   * @return positive count
   */
  public int tailPreserved() {
    return tailPreserved;
  }

  @Override
  public List<Message> compact(List<Message> history, SessionState state) {
    Objects.requireNonNull(history, "history must not be null");
    Objects.requireNonNull(state, "state must not be null");

    if (history.size() <= headPreserved + tailPreserved) {
      return history;
    }

    var head = history.subList(0, headPreserved);
    var middle = history.subList(headPreserved, history.size() - tailPreserved);
    var tail = history.subList(history.size() - tailPreserved, history.size());

    String summary;
    try {
      var request = new ArrayList<Message>(2);
      request.add(Message.system(summaryPrompt));
      request.add(Message.user(renderMiddle(middle)));
      var response = summaryModel.chat(List.copyOf(request));
      summary = response == null ? null : response.content();
    } catch (RuntimeException e) {
      LOG.log(
          Level.WARNING,
          e,
          () ->
              "Context compaction summary call failed; leaving history "
                  + "unchanged. Session "
                  + state.sessionId()
                  + " turn "
                  + state.currentTurnIndex());
      return history;
    }

    if (Strings.isBlank(summary)) {
      LOG.warning(
          () ->
              "Context compaction summary returned blank; leaving history unchanged. Session "
                  + state.sessionId()
                  + " turn "
                  + state.currentTurnIndex());
      return history;
    }

    var summaryMessage = Message.user(SUMMARY_PREFIX + summary);
    var newHistory = new ArrayList<Message>(headPreserved + 1 + tailPreserved);
    newHistory.addAll(head);
    newHistory.add(summaryMessage);
    newHistory.addAll(tail);
    return List.copyOf(newHistory);
  }

  private static String renderMiddle(List<Message> middle) {
    var sb = new StringBuilder();
    for (var m : middle) {
      sb.append('[').append(m.role().name().toLowerCase(java.util.Locale.ROOT)).append("] ");
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

    private Builder(Model summaryModel) {
      this.summaryModel = Objects.requireNonNull(summaryModel, "summaryModel must not be null");
    }

    /**
     * Set the number of head messages preserved across compaction. Default {@value
     * #DEFAULT_HEAD_PRESERVED}.
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
     * Set the number of tail messages preserved across compaction. Default {@value
     * #DEFAULT_TAIL_PRESERVED}.
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
     * Build the immutable compactor.
     *
     * @return the compactor
     */
    public DropMiddleToolResultsCompactor build() {
      return new DropMiddleToolResultsCompactor(this);
    }
  }
}
