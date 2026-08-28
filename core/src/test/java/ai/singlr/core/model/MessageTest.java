/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MessageTest {

  @Test
  void systemMessage() {
    var msg = Message.system("You are a helpful assistant.");

    assertEquals(Role.SYSTEM, msg.role());
    assertEquals("You are a helpful assistant.", msg.content());
    assertTrue(msg.toolCalls().isEmpty());
    assertNull(msg.toolCallId());
    assertFalse(msg.hasToolCalls());
  }

  @Test
  void userMessage() {
    var msg = Message.user("Hello!");

    assertEquals(Role.USER, msg.role());
    assertEquals("Hello!", msg.content());
  }

  @Test
  void assistantMessage() {
    var msg = Message.assistant("Hi there!");

    assertEquals(Role.ASSISTANT, msg.role());
    assertEquals("Hi there!", msg.content());
  }

  @Test
  void assistantWithToolCalls() {
    var toolCall =
        ToolCall.newBuilder()
            .withId("call_123")
            .withName("search")
            .withArguments(Map.of("query", "weather"))
            .build();

    var msg = Message.assistant(List.of(toolCall));

    assertEquals(Role.ASSISTANT, msg.role());
    assertNull(msg.content());
    assertTrue(msg.hasToolCalls());
    assertEquals(1, msg.toolCalls().size());
    assertEquals("search", msg.toolCalls().getFirst().name());
  }

  @Test
  void toolResultMessage() {
    var msg = Message.tool("call_123", "get_weather", "The weather is sunny.");

    assertEquals(Role.TOOL, msg.role());
    assertEquals("The weather is sunny.", msg.content());
    assertEquals("call_123", msg.toolCallId());
    assertEquals("get_weather", msg.toolName());
  }

  @Test
  void builderPattern() {
    var msg = Message.newBuilder().withRole(Role.USER).withContent("Test message").build();

    assertEquals(Role.USER, msg.role());
    assertEquals("Test message", msg.content());
  }

  @Test
  void copyBuilder() {
    var original = Message.user("Original");
    var copy = Message.newBuilder(original).withContent("Modified").build();

    assertEquals("Original", original.content());
    assertEquals("Modified", copy.content());
    assertEquals(Role.USER, copy.role());
  }

  @Test
  void allRoles() {
    assertEquals(Role.SYSTEM, Role.valueOf("SYSTEM"));
    assertEquals(Role.USER, Role.valueOf("USER"));
    assertEquals(Role.ASSISTANT, Role.valueOf("ASSISTANT"));
    assertEquals(Role.TOOL, Role.valueOf("TOOL"));
    assertEquals(4, Role.values().length);
  }

  @Test
  void builderWithToolCallId() {
    var msg =
        Message.newBuilder()
            .withRole(Role.TOOL)
            .withContent("Result")
            .withToolCallId("call_123")
            .build();

    assertEquals(Role.TOOL, msg.role());
    assertEquals("call_123", msg.toolCallId());
  }

  @Test
  void builderWithToolCalls() {
    var toolCall =
        ToolCall.newBuilder().withId("call_1").withName("test").withArguments(Map.of()).build();
    var msg =
        Message.newBuilder().withRole(Role.ASSISTANT).withToolCalls(List.of(toolCall)).build();

    assertTrue(msg.hasToolCalls());
    assertEquals(1, msg.toolCalls().size());
  }

  @Test
  void assistantWithContentAndToolCalls() {
    var toolCall = ToolCall.newBuilder().withId("call_1").withName("test").build();
    var msg = Message.assistant("Thinking...", List.of(toolCall));

    assertEquals(Role.ASSISTANT, msg.role());
    assertEquals("Thinking...", msg.content());
    assertTrue(msg.hasToolCalls());
  }

  @Test
  void hasToolCallsWithNullToolCalls() {
    var msg = new Message(Role.ASSISTANT, "content", null, null, null, null, null);

    assertFalse(msg.hasToolCalls());
  }

  @Test
  void builderWithNullToolCalls() {
    var msg = Message.newBuilder().withRole(Role.ASSISTANT).withToolCalls(null).build();

    assertFalse(msg.hasToolCalls());
    assertTrue(msg.toolCalls().isEmpty());
  }

  @Test
  void builderWithoutRoleThrows() {
    assertThrows(
        IllegalStateException.class, () -> Message.newBuilder().withContent("no role").build());
  }

  @Test
  void builderWithToolName() {
    var msg =
        Message.newBuilder()
            .withRole(Role.TOOL)
            .withContent("Result")
            .withToolCallId("call_1")
            .withToolName("get_weather")
            .build();

    assertEquals("get_weather", msg.toolName());
  }

  @Test
  void builderWithMetadata() {
    var metadata = Map.of("thought_sigs", "abc123");
    var msg =
        Message.newBuilder()
            .withRole(Role.ASSISTANT)
            .withContent("Hello")
            .withMetadata(metadata)
            .build();

    assertEquals("abc123", msg.metadata().get("thought_sigs"));
  }

  @Test
  void builderWithNullMetadataDefaultsToEmpty() {
    var msg = Message.newBuilder().withRole(Role.USER).withContent("Hi").withMetadata(null).build();

    assertTrue(msg.metadata().isEmpty());
  }

  @Test
  void assistantWithContentToolCallsAndMetadata() {
    var toolCall = ToolCall.newBuilder().withId("call_1").withName("test").build();
    var metadata = Map.of("sig", "xyz");
    var msg = Message.assistant("Thinking...", List.of(toolCall), metadata);

    assertEquals("Thinking...", msg.content());
    assertTrue(msg.hasToolCalls());
    assertEquals("xyz", msg.metadata().get("sig"));
  }

  @Test
  void userMessageWithInlineFiles() {
    var file = InlineFile.of(new byte[] {1, 2, 3}, "image/png");
    var msg = Message.user("Describe this image", List.of(file));

    assertEquals(Role.USER, msg.role());
    assertEquals("Describe this image", msg.content());
    assertTrue(msg.hasInlineFiles());
    assertEquals(1, msg.inlineFiles().size());
    assertEquals("image/png", msg.inlineFiles().getFirst().mimeType());
  }

  @Test
  void userMessageWithNullInlineFilesThrows() {
    // Null inlineFiles is rejected — callers pass List.of() explicitly when there are none.
    assertThrows(NullPointerException.class, () -> Message.user("Hello", null));
  }

  @Test
  void hasInlineFilesReturnsFalseForNonUserMessages() {
    assertFalse(Message.system("sys").hasInlineFiles());
    assertFalse(Message.assistant("hi").hasInlineFiles());
    assertFalse(Message.tool("c1", "t", "r").hasInlineFiles());
  }

  @Test
  void factoryMethodsDefaultToEmptyInlineFiles() {
    assertTrue(Message.user("hello").inlineFiles().isEmpty());
    assertTrue(Message.system("sys").inlineFiles().isEmpty());
    assertTrue(Message.assistant("hi").inlineFiles().isEmpty());
    assertTrue(Message.tool("c1", "t", "r").inlineFiles().isEmpty());
  }

  @Test
  void builderWithInlineFiles() {
    var file = InlineFile.of(new byte[] {0x50, 0x44, 0x46}, "application/pdf");
    var msg =
        Message.newBuilder()
            .withRole(Role.USER)
            .withContent("Extract text")
            .withInlineFiles(List.of(file))
            .build();

    assertTrue(msg.hasInlineFiles());
    assertEquals(1, msg.inlineFiles().size());
  }

  @Test
  void builderWithNullInlineFilesDefaultsToEmpty() {
    var msg =
        Message.newBuilder().withRole(Role.USER).withContent("Hi").withInlineFiles(null).build();

    assertFalse(msg.hasInlineFiles());
    assertTrue(msg.inlineFiles().isEmpty());
  }

  @Test
  void hasInlineFilesWithNullList() {
    var msg = new Message(Role.USER, "content", List.of(), null, null, Map.of(), null);

    assertFalse(msg.hasInlineFiles());
  }

  @Test
  void copyBuilderPreservesInlineFiles() {
    var file = InlineFile.of(new byte[] {1}, "image/jpeg");
    var original = Message.user("Describe", List.of(file));
    var copy = Message.newBuilder(original).withContent("Analyze").build();

    assertEquals("Analyze", copy.content());
    assertTrue(copy.hasInlineFiles());
    assertEquals(1, copy.inlineFiles().size());
  }

  @Test
  void builderWithFileReferences() {
    var file = FileReference.of("https://example.com/video.mp4", "video/mp4");
    var mutable = new java.util.ArrayList<>(List.of(file));

    var msg =
        Message.newBuilder()
            .withRole(Role.USER)
            .withContent("Summarize")
            .withFileReferences(mutable)
            .build();
    mutable.clear();

    assertTrue(msg.hasFileReferences());
    assertEquals(List.of(file), msg.fileReferences());
  }

  @Test
  void builderWithNullFileReferencesDefaultsToEmpty() {
    var msg =
        Message.newBuilder().withRole(Role.USER).withContent("Hi").withFileReferences(null).build();

    assertFalse(msg.hasFileReferences());
    assertTrue(msg.fileReferences().isEmpty());
  }

  @Test
  void copyBuilderPreservesFileReferences() {
    var file = FileReference.of("gs://bucket/video.mp4", "video/mp4");
    var original =
        Message.newBuilder()
            .withRole(Role.USER)
            .withContent("Summarize")
            .withFileReferences(List.of(file))
            .build();

    var copy = Message.newBuilder(original).withContent("Describe").build();

    assertEquals(List.of(file), copy.fileReferences());
  }

  @Test
  void legacyConstructorDefaultsFileReferencesToEmpty() {
    var msg = new Message(Role.USER, "content", List.of(), null, null, Map.of(), List.of());

    assertTrue(msg.fileReferences().isEmpty());
  }

  @Test
  void canonicalConstructorDefaultsNullFileReferencesToEmpty() {
    var msg = new Message(Role.USER, "content", List.of(), null, null, Map.of(), List.of(), null);

    assertTrue(msg.fileReferences().isEmpty());
  }
}
