/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.openai;

import static com.standardapplied.helios.openai.OpenAIFixture.createModel;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.FileReference;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Role;
import com.standardapplied.helios.core.model.ToolCall;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenAIInputTest {

  @Test
  void convertAssistantMessageSimpleText() {
    var model = createModel();
    var message = Message.assistant("Hello");

    var items = OpenAIInput.assistant(message);

    assertEquals(1, items.size());
    assertEquals("message", items.getFirst().type());
    assertEquals("assistant", items.getFirst().role());
  }

  @Test
  void convertAssistantMessageWithToolCalls() {
    var model = createModel();
    var tc =
        ToolCall.newBuilder()
            .withId("call_1")
            .withName("search")
            .withArguments(Map.of("q", "test"))
            .build();
    var message = Message.assistant("I'll search", List.of(tc));

    var items = OpenAIInput.assistant(message);

    assertEquals(2, items.size());
    assertEquals("message", items.get(0).type());
    assertEquals("function_call", items.get(1).type());
    assertEquals("call_1", items.get(1).callId());
    assertEquals("search", items.get(1).name());
  }

  @Test
  void convertAssistantMessageToolCallsOnly() {
    var model = createModel();
    var tc =
        ToolCall.newBuilder()
            .withId("call_1")
            .withName("search")
            .withArguments(Map.of("q", "test"))
            .build();
    var message = Message.assistant(List.of(tc));

    var items = OpenAIInput.assistant(message);

    assertEquals(1, items.size());
    assertEquals("function_call", items.getFirst().type());
  }

  @Test
  void convertAssistantMessageNullContentBecomesEmpty() {
    var model = createModel();
    var message = new Message(Role.ASSISTANT, null, List.of(), null, null, Map.of(), List.of());

    var items = OpenAIInput.assistant(message);

    assertEquals(1, items.size());
    assertEquals("message", items.getFirst().type());
  }

  @Test
  void convertAssistantMessageEmptyContentBecomesEmpty() {
    var model = createModel();
    var message = new Message(Role.ASSISTANT, "", List.of(), null, null, Map.of(), List.of());

    var items = OpenAIInput.assistant(message);

    assertEquals(1, items.size());
    assertEquals("message", items.getFirst().type());
  }

  @Test
  void convertAssistantMessageWithNullArguments() {
    var model = createModel();
    var tc = ToolCall.newBuilder().withId("call_1").withName("fn").build();
    var message = Message.assistant(List.of(tc));

    var items = OpenAIInput.assistant(message);

    assertEquals(1, items.size());
    assertEquals("function_call", items.getFirst().type());
    assertEquals("{}", items.getFirst().arguments());
  }

  @Test
  void rejectsProviderFileReferencesInsteadOfSilentlyDroppingThem() {
    var message =
        Message.newBuilder()
            .withRole(Role.USER)
            .withContent("Summarize")
            .withFileReferences(
                List.of(FileReference.of("https://example.com/video.mp4", "video/mp4")))
            .build();

    var error = assertThrows(IllegalArgumentException.class, () -> OpenAIInput.user(message));

    assertTrue(error.getMessage().contains("file references"));
  }
}
