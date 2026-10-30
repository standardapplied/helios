/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.model.FileReference;
import com.standardapplied.helios.core.model.InlineFile;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Role;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.HookPhase;
import java.util.ArrayList;
import java.util.List;

/**
 * Drains the steering queue into the history at an iteration boundary. Each pending {@link
 * UserMessage} fires the on-user-message hooks — {@link HookOutcome.Block Block} drops the message,
 * {@link HookOutcome.MutateText MutateText} rewrites its text, {@link HookOutcome.Stop Stop} ends
 * the run. Surviving messages emit {@link QueryEvent.UserMessageReceived} and compose, with their
 * attachments and file references, into a single user-role message.
 */
final class SteeringDrain {

  private final LoopCollaborators collaborators;
  private final EventEmitter emitter;
  private final HookEffects effects;

  SteeringDrain(LoopCollaborators collaborators, EventEmitter emitter, HookEffects effects) {
    this.collaborators = collaborators;
    this.emitter = emitter;
    this.effects = effects;
  }

  /**
   * Drain the queue and append what survives the hooks; stops early on a hook's {@code Stop}.
   *
   * @return whether a user message was appended to the history
   */
  boolean drainInto(SessionState state) {
    var drained = collaborators.steeringQueue().drain();
    if (drained.isEmpty()) {
      return false;
    }
    var accepted = new ArrayList<UserMessage>();
    for (var message : drained) {
      var decision =
          collaborators.hooks().fireOnUserMessage(message, collaborators.hookContext(state));
      var hookName = decision.firingHookName();
      switch (decision.outcome()) {
        case HookOutcome.MutateText mutate ->
            accepted.add(mutateText(state, message, hookName, mutate));
        case HookOutcome.Block block -> block(state, message, hookName, block);
        case HookOutcome.Stop stop -> {
          effects.stop(state, hookName, HookPhase.ON_USER_MESSAGE, stop);
          return false;
        }
        case HookOutcome.Continue _ -> accepted.add(receive(state, message));
      }
    }
    if (accepted.isEmpty()) {
      return false;
    }
    state
        .history()
        .append(
            Message.newBuilder()
                .withRole(Role.USER)
                .withContent(composeContent(accepted))
                .withInlineFiles(collectAttachments(accepted))
                .withFileReferences(collectFileReferences(accepted))
                .build());
    return true;
  }

  private UserMessage receive(SessionState state, UserMessage message) {
    emitter.emit(
        state,
        new QueryEvent.UserMessageReceived(
            state.sessionId(),
            state.runId(),
            state.currentTurnIndex(),
            collaborators.clock().instant(),
            message));
    return message;
  }

  private UserMessage mutateText(
      SessionState state, UserMessage message, String hookName, HookOutcome.MutateText mutate) {
    var replacement =
        new UserMessage(mutate.text(), message.attachments(), message.fileReferences());
    emitter.emitHookFired(state, hookName, HookPhase.ON_USER_MESSAGE, mutate);
    return receive(state, replacement);
  }

  private void block(
      SessionState state, UserMessage message, String hookName, HookOutcome.Block block) {
    emitter.emitHookFired(state, hookName, HookPhase.ON_USER_MESSAGE, block);
    emitter.emit(
        state,
        new QueryEvent.MessageBlocked(
            state.sessionId(),
            state.runId(),
            state.currentTurnIndex(),
            collaborators.clock().instant(),
            message,
            hookName,
            RedactedReason.of(block.reason(), collaborators.secrets()).text()));
  }

  private static List<InlineFile> collectAttachments(List<UserMessage> messages) {
    var attachments = new ArrayList<InlineFile>();
    for (var m : messages) {
      attachments.addAll(m.attachments());
    }
    return attachments;
  }

  private static List<FileReference> collectFileReferences(List<UserMessage> messages) {
    var references = new ArrayList<FileReference>();
    for (var message : messages) {
      references.addAll(message.fileReferences());
    }
    return references;
  }

  private static String composeContent(List<UserMessage> messages) {
    if (messages.size() == 1) {
      return messages.get(0).text();
    }
    var joined = new StringBuilder();
    joined.append("[messages composed: ").append(messages.size()).append("]\n");
    for (var i = 0; i < messages.size(); i++) {
      if (i > 0) {
        joined.append("\n");
      }
      joined.append(messages.get(i).text());
    }
    return joined.toString();
  }
}
