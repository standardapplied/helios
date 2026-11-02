/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.context.TokenCounter;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * The conversation history of one session, which every run of it shares: the messages sent to the
 * model on every turn, appended by the session and the loop and replaced wholesale only by the loop
 * (compaction, or a {@code PreModelTurnHook} returning {@code MutateHistory}). It also records
 * whether the session's {@code ContextWarning} has fired for this history: at most once, until a
 * replacement re-arms it; how many replacements there have been, so the session's store learns of
 * each; and the anchor of its token count: the size of the last request the provider reported,
 * valid until a replacement or a turn without a report clears it.
 *
 * <h2>Thread-safety</h2>
 *
 * Backed by a {@link CopyOnWriteArrayList}: the loop thread writes, any thread may take a snapshot.
 */
public final class ConversationHistory {

  private final CopyOnWriteArrayList<Message> messages = new CopyOnWriteArrayList<>();
  private final AtomicBoolean contextWarningFired = new AtomicBoolean(false);
  private final AtomicLong replacements = new AtomicLong();
  private volatile Anchor anchor;

  ConversationHistory() {}

  /**
   * Close the open tool round, if any: when the last assistant message with tool calls has calls no
   * tool message answers, append one tool message per unanswered call, in call order, with {@code
   * content}. A provider rejects a history whose tool calls go unanswered, so a run that ended
   * mid-round leaves the next run a history it accepts.
   */
  void closeOpenRound(String content) {
    var snapshot = snapshot();
    for (var i = snapshot.size() - 1; i >= 0; i--) {
      var message = snapshot.get(i);
      if (message.role() == Role.ASSISTANT && message.hasToolCalls()) {
        var answered =
            snapshot.subList(i + 1, snapshot.size()).stream()
                .filter(later -> later.role() == Role.TOOL)
                .map(Message::toolCallId)
                .collect(Collectors.toSet());
        message.toolCalls().stream()
            .filter(call -> !answered.contains(call.id()))
            .forEach(call -> append(Message.tool(call.id(), call.name(), content)));
        return;
      }
    }
  }

  /**
   * Append a message to the conversation history.
   *
   * @param message the message to append; non-null
   * @throws NullPointerException if {@code message} is null
   */
  public void append(Message message) {
    Objects.requireNonNull(message, "message must not be null");
    messages.add(message);
  }

  /**
   * Immutable snapshot of the conversation history at the moment of the call. The returned list
   * never reflects subsequent appends — a fresh call returns a fresh snapshot.
   *
   * @return a defensive immutable copy of the history
   */
  public List<Message> snapshot() {
    return List.copyOf(messages);
  }

  /**
   * Replace the entire history with the given list, defensively copied so subsequent caller
   * mutations don't leak in, and re-arm the context warning so a climb of the new history back
   * through the watermark fires it again. The anchor is cleared: it measured the old messages.
   *
   * @throws NullPointerException if {@code newHistory} or any element is null
   */
  void replace(List<Message> newHistory) {
    Objects.requireNonNull(newHistory, "newHistory must not be null");
    var copy = new ArrayList<Message>(newHistory.size());
    for (var m : newHistory) {
      copy.add(Objects.requireNonNull(m, "newHistory must not contain null"));
    }
    messages.clear();
    messages.addAll(copy);
    contextWarningFired.set(false);
    anchor = null;
    replacements.incrementAndGet();
  }

  /** How many times {@link #replace(List)} ran. */
  long replacements() {
    return replacements.get();
  }

  /** How many messages the history holds. */
  int size() {
    return messages.size();
  }

  /**
   * Anchor the token count on the provider's report of a turn of the session's model.
   *
   * @param requestMessages how many messages the turn's request was built from
   * @param usage what the turn reported; one with no prompt tokens clears the anchor
   */
  void anchor(int requestMessages, Usage usage) {
    var tokens = usage.promptTokens();
    anchor = tokens > 0 ? new Anchor(requestMessages, tokens) : null;
  }

  /**
   * The tokens the next request starts from: the anchor plus {@code counter}'s estimate of the
   * messages appended since it, or with no anchor the estimate of the whole history.
   */
  long tokens(TokenCounter counter) {
    var history = snapshot();
    var anchored = anchor;
    return anchored == null
        ? counter.count(history)
        : Math.addExact(
            anchored.tokens(), counter.count(history.subList(anchored.messages(), history.size())));
  }

  /**
   * Mark the context warning as fired for this history.
   *
   * @return {@code true} if this call fired it (the caller emits the event); {@code false} if it
   *     already fired since the last {@link #replace(List)}
   */
  boolean tryFireContextWarning() {
    return contextWarningFired.compareAndSet(false, true);
  }

  /** The prompt tokens the provider reported for a request of the first {@code messages}. */
  private record Anchor(int messages, long tokens) {}
}
