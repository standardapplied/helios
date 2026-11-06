/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.model;

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
 * @param fileReferences provider-accessible file references for multimodal input (for USER role)
 */
public record Message(
    Role role,
    String content,
    List<ToolCall> toolCalls,
    String toolCallId,
    String toolName,
    Map<String, String> metadata,
    List<InlineFile> inlineFiles,
    List<FileReference> fileReferences) {

  /**
   * Canonical constructor. A {@code null} {@code fileReferences} becomes an empty list, and the
   * list is copied; every other component is kept as given.
   *
   * @throws NullPointerException if {@code fileReferences} holds a null element
   */
  public Message {
    fileReferences = fileReferences == null ? List.of() : List.copyOf(fileReferences);
  }

  /**
   * Creates a message with no file references, every other component as the canonical constructor
   * takes it.
   *
   * @param role the role of the message sender
   * @param content the text content of the message
   * @param toolCalls tool calls requested by the assistant (for ASSISTANT role)
   * @param toolCallId the ID of the tool call this message responds to (for TOOL role)
   * @param toolName the name of the tool that was called (for TOOL role)
   * @param metadata provider-specific data for round-tripping (e.g., thought signatures)
   * @param inlineFiles inline file attachments for multimodal input (for USER role)
   */
  public Message(
      Role role,
      String content,
      List<ToolCall> toolCalls,
      String toolCallId,
      String toolName,
      Map<String, String> metadata,
      List<InlineFile> inlineFiles) {
    this(role, content, toolCalls, toolCallId, toolName, metadata, inlineFiles, List.of());
  }

  /**
   * Starts an empty builder: no role, no content, and every list and map empty.
   *
   * @return a new builder
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Starts a builder holding every component of {@code message}, so a copy can differ in a few.
   *
   * @param message the message to copy; non-null
   * @return a new builder initialised from {@code message}
   * @throws NullPointerException if {@code message} is null
   */
  public static Builder newBuilder(Message message) {
    return new Builder(message);
  }

  /**
   * Creates a system message: the instructions a provider sends as its system prompt.
   *
   * @param content the instructions; may be null
   * @return a {@link Role#SYSTEM} message with no tool calls, metadata or attachments
   */
  public static Message system(String content) {
    return new Message(Role.SYSTEM, content, List.of(), null, null, Map.of(), List.of(), List.of());
  }

  /**
   * Creates a text-only user message.
   *
   * @param content the user's text; may be null
   * @return a {@link Role#USER} message with no tool calls, metadata or attachments
   */
  public static Message user(String content) {
    return new Message(Role.USER, content, List.of(), null, null, Map.of(), List.of(), List.of());
  }

  /**
   * Creates a user message with inline attachments, such as images or PDFs sent alongside the text.
   *
   * @param content the user's text; may be null
   * @param inlineFiles the attachments, copied; non-null, empty for none
   * @return a {@link Role#USER} message carrying {@code inlineFiles}
   * @throws NullPointerException if {@code inlineFiles} is null or holds a null element
   */
  public static Message user(String content, List<InlineFile> inlineFiles) {
    if (inlineFiles == null) {
      throw new NullPointerException("inlineFiles must not be null — pass List.of() for none");
    }
    return new Message(
        Role.USER, content, List.of(), null, null, Map.of(), List.copyOf(inlineFiles), List.of());
  }

  /**
   * Creates an assistant message holding text only, such as a final answer replayed into history.
   *
   * @param content the assistant's text; may be null
   * @return a {@link Role#ASSISTANT} message with no tool calls or metadata
   */
  public static Message assistant(String content) {
    return new Message(
        Role.ASSISTANT, content, List.of(), null, null, Map.of(), List.of(), List.of());
  }

  /**
   * Creates an assistant message that only requests tool calls.
   *
   * @param toolCalls the calls the model requested, copied; non-null
   * @return a {@link Role#ASSISTANT} message with {@code null} content and {@code toolCalls}
   * @throws NullPointerException if {@code toolCalls} is null or holds a null element
   */
  public static Message assistant(List<ToolCall> toolCalls) {
    return new Message(
        Role.ASSISTANT, null, List.copyOf(toolCalls), null, null, Map.of(), List.of(), List.of());
  }

  /**
   * Creates an assistant message with text and the tool calls requested alongside it.
   *
   * @param content the assistant's text; may be null
   * @param toolCalls the calls the model requested, copied; non-null, empty for none
   * @return a {@link Role#ASSISTANT} message with no metadata
   * @throws NullPointerException if {@code toolCalls} is null or holds a null element
   */
  public static Message assistant(String content, List<ToolCall> toolCalls) {
    return new Message(
        Role.ASSISTANT,
        content,
        List.copyOf(toolCalls),
        null,
        null,
        Map.of(),
        List.of(),
        List.of());
  }

  /**
   * Creates an assistant message carrying the provider metadata its turn produced, such as thinking
   * signatures, so the provider can be sent the turn back verbatim on the next request.
   *
   * @param content the assistant's text; may be null
   * @param toolCalls the calls the model requested, copied; non-null, empty for none
   * @param metadata the provider's round-trip data, copied; {@code null} is treated as empty
   * @return a {@link Role#ASSISTANT} message
   * @throws NullPointerException if {@code toolCalls} is null, or a list or map holds a null
   */
  public static Message assistant(
      String content, List<ToolCall> toolCalls, Map<String, String> metadata) {
    return new Message(
        Role.ASSISTANT,
        content,
        List.copyOf(toolCalls),
        null,
        null,
        metadata != null ? Map.copyOf(metadata) : Map.of(),
        List.of(),
        List.of());
  }

  /**
   * Creates the message that answers one tool call with the tool's output.
   *
   * @param toolCallId the {@link ToolCall#id()} this result answers; providers pair them by it
   * @param toolName the {@link ToolCall#name()} of the tool that ran
   * @param content the tool's output as the model sees it
   * @return a {@link Role#TOOL} message
   */
  public static Message tool(String toolCallId, String toolName, String content) {
    return new Message(
        Role.TOOL, content, List.of(), toolCallId, toolName, Map.of(), List.of(), List.of());
  }

  /**
   * Whether this message requests at least one tool call.
   *
   * @return {@code true} if {@link #toolCalls()} is non-null and non-empty
   */
  public boolean hasToolCalls() {
    return toolCalls != null && !toolCalls.isEmpty();
  }

  /**
   * Whether this message carries at least one inline attachment.
   *
   * @return {@code true} if {@link #inlineFiles()} is non-null and non-empty
   */
  public boolean hasInlineFiles() {
    return inlineFiles != null && !inlineFiles.isEmpty();
  }

  /**
   * Whether this message references at least one provider-accessible file.
   *
   * @return {@code true} if {@link #fileReferences()} is non-empty
   */
  public boolean hasFileReferences() {
    return fileReferences != null && !fileReferences.isEmpty();
  }

  /**
   * Builds a {@link Message}. The {@code with*} methods for lists and maps copy their argument and
   * treat {@code null} as empty; {@link #build()} requires a role. Not thread-safe.
   */
  public static class Builder {
    private Role role;
    private String content;
    private List<ToolCall> toolCalls = List.of();
    private String toolCallId;
    private String toolName;
    private Map<String, String> metadata = Map.of();
    private List<InlineFile> inlineFiles = List.of();
    private List<FileReference> fileReferences = List.of();

    private Builder() {}

    private Builder(Message message) {
      this.role = message.role;
      this.content = message.content;
      this.toolCalls = message.toolCalls;
      this.toolCallId = message.toolCallId;
      this.toolName = message.toolName;
      this.metadata = message.metadata;
      this.inlineFiles = message.inlineFiles;
      this.fileReferences = message.fileReferences;
    }

    /**
     * Sets who sent the message. Required; unset by default.
     *
     * @param role the sender's role
     * @return this builder
     */
    public Builder withRole(Role role) {
      this.role = role;
      return this;
    }

    /**
     * Sets the message's text. {@code null} by default, as for an assistant turn that only calls
     * tools.
     *
     * @param content the text; may be null
     * @return this builder
     */
    public Builder withContent(String content) {
      this.content = content;
      return this;
    }

    /**
     * Sets the tool calls an assistant message requests. Empty by default.
     *
     * @param toolCalls the calls, copied; {@code null} is treated as empty
     * @return this builder
     * @throws NullPointerException if {@code toolCalls} holds a null element
     */
    public Builder withToolCalls(List<ToolCall> toolCalls) {
      this.toolCalls = toolCalls != null ? List.copyOf(toolCalls) : List.of();
      return this;
    }

    /**
     * Sets the {@link ToolCall#id()} a tool message answers. {@code null} by default, as for every
     * role but {@link Role#TOOL}.
     *
     * @param toolCallId the answered call's id; may be null
     * @return this builder
     */
    public Builder withToolCallId(String toolCallId) {
      this.toolCallId = toolCallId;
      return this;
    }

    /**
     * Sets the name of the tool a tool message reports on. {@code null} by default, as for every
     * role but {@link Role#TOOL}.
     *
     * @param toolName the tool's name; may be null
     * @return this builder
     */
    public Builder withToolName(String toolName) {
      this.toolName = toolName;
      return this;
    }

    /**
     * Sets the provider-specific round-trip data, such as thinking signatures. Empty by default.
     *
     * @param metadata the metadata, copied; {@code null} is treated as empty
     * @return this builder
     * @throws NullPointerException if {@code metadata} holds a null key or value
     */
    public Builder withMetadata(Map<String, String> metadata) {
      this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
      return this;
    }

    /**
     * Sets the inline attachments of a user message. Empty by default.
     *
     * @param inlineFiles the attachments, copied; {@code null} is treated as empty
     * @return this builder
     * @throws NullPointerException if {@code inlineFiles} holds a null element
     */
    public Builder withInlineFiles(List<InlineFile> inlineFiles) {
      this.inlineFiles = inlineFiles != null ? List.copyOf(inlineFiles) : List.of();
      return this;
    }

    /**
     * Sets the provider-accessible files a user message references. Empty by default.
     *
     * @param fileReferences the references, copied; {@code null} is treated as empty
     * @return this builder
     * @throws NullPointerException if {@code fileReferences} holds a null element
     */
    public Builder withFileReferences(List<FileReference> fileReferences) {
      this.fileReferences = fileReferences != null ? List.copyOf(fileReferences) : List.of();
      return this;
    }

    /**
     * Builds the message.
     *
     * @return the message
     * @throws IllegalStateException if no role was set
     */
    public Message build() {
      if (role == null) {
        throw new IllegalStateException("Role is required");
      }
      return new Message(
          role, content, toolCalls, toolCallId, toolName, metadata, inlineFiles, fileReferences);
    }
  }
}
