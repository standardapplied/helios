/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Role;
import com.standardapplied.helios.gemini.api.ContentItem;
import com.standardapplied.helios.gemini.api.Step;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * A conversation as Interactions API input steps: the last system message as the system
 * instruction, and every other message one or more steps.
 *
 * @param steps the input steps
 * @param systemInstruction the system instruction, or {@code null} when there is none
 */
record GeminiConversation(List<Step> steps, String systemInstruction) {

  /** {@code messages} converted. */
  static GeminiConversation of(List<Message> messages) {
    var steps = new ArrayList<Step>();
    String systemInstruction = null;
    for (var message : messages) {
      switch (message.role()) {
        case SYSTEM -> systemInstruction = message.content();
        case USER -> steps.add(userStep(message));
        case ASSISTANT -> appendAssistantSteps(message, steps);
        case TOOL -> steps.add(toolResult(message));
      }
    }
    return new GeminiConversation(steps, systemInstruction);
  }

  /**
   * The steps of the messages from {@code startIndex} on, sent when continuing server-side: user
   * turns and tool results only, since the server replays the assistant turns.
   */
  static List<Step> continuationSteps(List<Message> messages, int startIndex) {
    var steps = new ArrayList<Step>();
    for (var message : messages.subList(startIndex, messages.size())) {
      switch (message.role()) {
        case TOOL -> steps.add(toolResult(message));
        case USER -> steps.add(userStep(message));
        case SYSTEM, ASSISTANT -> {
          // Replayed by the server from previous_interaction_id.
        }
      }
    }
    return steps;
  }

  /** The content of the last system message in {@code messages}, or {@code null}. */
  static String extractSystemInstruction(List<Message> messages) {
    String instruction = null;
    for (var message : messages) {
      if (message.role() == Role.SYSTEM) {
        instruction = message.content();
      }
    }
    return instruction;
  }

  /** The Interactions API content type of a file of {@code mimeType}. */
  static String interactionsContentType(String mimeType) {
    if (mimeType.startsWith("image/")) {
      return "image";
    }
    if (mimeType.startsWith("audio/")) {
      return "audio";
    }
    return mimeType.startsWith("video/") ? "video" : "document";
  }

  private static Step toolResult(Message message) {
    return Step.functionResult(message.toolCallId(), message.toolName(), message.content());
  }

  private static Step userStep(Message message) {
    if (!message.hasInlineFiles() && !message.hasFileReferences()) {
      return Step.userInput(message.content());
    }
    var items = new ArrayList<ContentItem>();
    if (message.hasInlineFiles()) {
      for (var file : message.inlineFiles()) {
        var base64 = Base64.getEncoder().encodeToString(file.data());
        items.add(
            ContentItem.inlineData(
                interactionsContentType(file.mimeType()), file.mimeType(), base64));
      }
    }
    for (var file : message.fileReferences()) {
      items.add(
          ContentItem.fileUri(
              interactionsContentType(file.mimeType()), file.mimeType(), file.uri()));
    }
    if (message.content() != null) {
      items.add(ContentItem.text(message.content()));
    }
    return Step.userInput(items);
  }

  /** A turn that called tools replays its thought signatures before its calls. */
  private static void appendAssistantSteps(Message message, List<Step> steps) {
    if (!message.hasToolCalls()) {
      steps.add(Step.modelOutput(message.content()));
      return;
    }
    var signatures = message.metadata().getOrDefault(GeminiModel.THOUGHT_SIGNATURES_KEY, "");
    if (!signatures.isEmpty()) {
      for (var signature : signatures.split(GeminiModel.SIGNATURE_DELIMITER)) {
        steps.add(Step.thought(signature));
      }
    }
    for (var call : message.toolCalls()) {
      steps.add(Step.functionCall(call.id(), call.name(), call.arguments()));
    }
  }
}
