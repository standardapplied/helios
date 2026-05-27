/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

import java.util.List;
import java.util.Map;

/**
 * A message in a conversation.
 *
 * @param role the role of the message sender
 * @param content the text content of the message
 * @param toolCalls tool calls requested by the assistant (for ASSISTANT role)
 * @param toolCallId the ID of the tool call this message responds to (for TOOL role)
 * @param toolName the name of the tool that was called (for TOOL role)
 * @param metadata provider-specific data for round-tripping (e.g., thought signatures)
 * @param inlineFiles inline file attachments for multimodal input (for USER role)
 */
public record Message(
    Role role,
    String content,
    List<ToolCall> toolCalls,
    String toolCallId,
    String toolName,
    Map<String, String> metadata,
    List<InlineFile> inlineFiles) {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(Message message) {
    return new Builder(message);
  }

  public static Message system(String content) {
    return new Message(Role.SYSTEM, content, List.of(), null, null, Map.of(), List.of());
  }

  public static Message user(String content) {
    return new Message(Role.USER, content, List.of(), null, null, Map.of(), List.of());
  }

  public static Message user(String content, List<InlineFile> inlineFiles) {
    if (inlineFiles == null) {
      throw new NullPointerException("inlineFiles must not be null — pass List.of() for none");
    }
    return new Message(
        Role.USER, content, List.of(), null, null, Map.of(), List.copyOf(inlineFiles));
  }

  public static Message assistant(String content) {
    return new Message(Role.ASSISTANT, content, List.of(), null, null, Map.of(), List.of());
  }

  public static Message assistant(List<ToolCall> toolCalls) {
    return new Message(
        Role.ASSISTANT, null, List.copyOf(toolCalls), null, null, Map.of(), List.of());
  }

  public static Message assistant(String content, List<ToolCall> toolCalls) {
    return new Message(
        Role.ASSISTANT, content, List.copyOf(toolCalls), null, null, Map.of(), List.of());
  }

  public static Message assistant(
      String content, List<ToolCall> toolCalls, Map<String, String> metadata) {
    return new Message(
        Role.ASSISTANT,
        content,
        List.copyOf(toolCalls),
        null,
        null,
        metadata != null ? Map.copyOf(metadata) : Map.of(),
        List.of());
  }

  public static Message tool(String toolCallId, String toolName, String content) {
    return new Message(Role.TOOL, content, List.of(), toolCallId, toolName, Map.of(), List.of());
  }

  public boolean hasToolCalls() {
    return toolCalls != null && !toolCalls.isEmpty();
  }

  public boolean hasInlineFiles() {
    return inlineFiles != null && !inlineFiles.isEmpty();
  }

  public static class Builder {
    private Role role;
    private String content;
    private List<ToolCall> toolCalls = List.of();
    private String toolCallId;
    private String toolName;
    private Map<String, String> metadata = Map.of();
    private List<InlineFile> inlineFiles = List.of();

    private Builder() {}

    private Builder(Message message) {
      this.role = message.role;
      this.content = message.content;
      this.toolCalls = message.toolCalls;
      this.toolCallId = message.toolCallId;
      this.toolName = message.toolName;
      this.metadata = message.metadata;
      this.inlineFiles = message.inlineFiles;
    }

    public Builder withRole(Role role) {
      this.role = role;
      return this;
    }

    public Builder withContent(String content) {
      this.content = content;
      return this;
    }

    public Builder withToolCalls(List<ToolCall> toolCalls) {
      this.toolCalls = toolCalls != null ? List.copyOf(toolCalls) : List.of();
      return this;
    }

    public Builder withToolCallId(String toolCallId) {
      this.toolCallId = toolCallId;
      return this;
    }

    public Builder withToolName(String toolName) {
      this.toolName = toolName;
      return this;
    }

    public Builder withMetadata(Map<String, String> metadata) {
      this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
      return this;
    }

    public Builder withInlineFiles(List<InlineFile> inlineFiles) {
      this.inlineFiles = inlineFiles != null ? List.copyOf(inlineFiles) : List.of();
      return this;
    }

    public Message build() {
      if (role == null) {
        throw new IllegalStateException("Role is required");
      }
      return new Message(role, content, toolCalls, toolCallId, toolName, metadata, inlineFiles);
    }
  }
}
