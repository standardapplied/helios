/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.openai;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.openai.api.ContentPart;
import com.standardapplied.helios.openai.api.InputItem;
import com.standardapplied.helios.openai.api.OpenAIJson;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * A conversation in the Responses API's shape: system messages joined into the instructions, and
 * every other message one or more input items.
 *
 * @param instructions the instructions, or {@code null} when the conversation has none
 * @param items the input items
 */
record OpenAIInput(String instructions, List<InputItem> items) {

  /** {@code messages} converted. */
  static OpenAIInput of(List<Message> messages) {
    var items = new ArrayList<InputItem>();
    String instructions = null;
    for (var message : messages) {
      switch (message.role()) {
        case SYSTEM -> instructions = appendSystemText(instructions, message.content());
        case USER -> items.add(user(message));
        case ASSISTANT -> items.addAll(assistant(message));
        case TOOL ->
            items.add(InputItem.functionCallOutput(message.toolCallId(), message.content()));
      }
    }
    return new OpenAIInput(instructions, items);
  }

  /**
   * A user turn: plain text as the bare string, or with inline files a content-part array carrying
   * each image or file beside the text.
   */
  static InputItem user(Message message) {
    if (message.hasFileReferences()) {
      throw new IllegalArgumentException(
          "OpenAI does not support URI file references through this message API");
    }
    var text = message.content() != null ? message.content() : "";
    if (!message.hasInlineFiles()) {
      return InputItem.userMessage(text);
    }
    var parts = new ArrayList<ContentPart>(message.inlineFiles().size() + 1);
    for (var file : message.inlineFiles()) {
      var data = Base64.getEncoder().encodeToString(file.data());
      var media = file.mimeType();
      parts.add(
          media != null && media.startsWith("image/")
              ? ContentPart.inputImage(media, data)
              : ContentPart.inputFile(media, data, null));
    }
    if (!text.isEmpty()) {
      parts.add(ContentPart.inputText(text));
    }
    return InputItem.userMessage(parts);
  }

  /** An assistant turn: its text, then one function call per tool call, never no item at all. */
  static List<InputItem> assistant(Message message) {
    var items = new ArrayList<InputItem>();
    if (message.content() != null && !message.content().isEmpty()) {
      items.add(InputItem.assistantMessage(message.content()));
    }
    if (message.hasToolCalls()) {
      for (var call : message.toolCalls()) {
        items.add(InputItem.functionCall(call.id(), call.name(), arguments(call.arguments())));
      }
    }
    if (items.isEmpty()) {
      items.add(InputItem.assistantMessage(""));
    }
    return items;
  }

  private static String appendSystemText(String existing, String additional) {
    return existing == null ? additional : existing + "\n\n" + additional;
  }

  private static String arguments(Map<String, Object> arguments) {
    if (arguments == null || arguments.isEmpty()) {
      return "{}";
    }
    try {
      return OpenAIJson.LENIENT.writeValueAsString(arguments);
    } catch (RuntimeException e) {
      throw new OpenAIException("Failed to serialize tool call arguments", e);
    }
  }
}
