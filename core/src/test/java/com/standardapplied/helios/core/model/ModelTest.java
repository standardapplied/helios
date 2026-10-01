/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.tool.Tool;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelTest {

  record SimpleOutput(String value) {}

  @Test
  void chatWithoutTools() {
    var model =
        new Model() {
          @Override
          public Response chat(List<Message> messages, List<Tool> tools) {
            assertTrue(tools.isEmpty());
            return Response.newBuilder()
                .withContent("Response")
                .withFinishReason(FinishReason.STOP)
                .build();
          }

          @Override
          public String id() {
            return "test-model";
          }

          @Override
          public String provider() {
            return "test";
          }
        };

    var response = model.chat(List.of(Message.user("Hello")));

    assertEquals("Response", response.content());
  }

  @Test
  void chatStreamDefaultImplementation() {
    var model =
        new Model() {
          @Override
          public Response chat(List<Message> messages, List<Tool> tools) {
            return Response.newBuilder()
                .withContent("Streamed response")
                .withFinishReason(FinishReason.STOP)
                .build();
          }

          @Override
          public String id() {
            return "test-model";
          }

          @Override
          public String provider() {
            return "test";
          }
        };

    var iterator = model.chatStream(List.of(Message.user("Hello")), List.of());

    assertTrue(iterator instanceof CloseableIterator<?>);
    assertTrue(iterator.hasNext());
    var event = iterator.next();
    assertTrue(event instanceof StreamEvent.Done);
    var doneEvent = (StreamEvent.Done) event;
    assertEquals("Streamed response", doneEvent.response().content());
    assertFalse(iterator.hasNext());
    iterator.close();
  }

  @Test
  void chatStreamWithoutToolsDefault() {
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            assertTrue(tools.isEmpty());
            return Response.newBuilder()
                .withContent("Streamed")
                .withFinishReason(FinishReason.STOP)
                .build();
          }

          @Override
          public String id() {
            return "test-model";
          }

          @Override
          public String provider() {
            return "test";
          }
        };

    try (var iterator = model.chatStream(List.of(Message.user("Hello")))) {
      assertTrue(iterator.hasNext());
      var event = iterator.next();
      assertTrue(event instanceof StreamEvent.Done);
    }
  }

  @Test
  void chatWithStructuredOutputDefaultThrows() {
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            return Response.newBuilder().withContent("ok").build();
          }

          @Override
          public String id() {
            return "test-model";
          }

          @Override
          public String provider() {
            return "test";
          }
        };

    assertThrows(
        UnsupportedOperationException.class,
        () ->
            model.chat(
                List.of(Message.user("Hello")), List.of(), OutputSchema.of(SimpleOutput.class)));
  }

  @Test
  void chatWithStructuredOutputNoToolsDefault() {
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            return Response.newBuilder().withContent("ok").build();
          }

          @Override
          public String id() {
            return "test-model";
          }

          @Override
          public String provider() {
            return "test";
          }
        };

    assertThrows(
        UnsupportedOperationException.class,
        () -> model.chat(List.of(Message.user("Hello")), OutputSchema.of(SimpleOutput.class)));
  }

  @Test
  void defaultCloseIsNoOpAndIdempotent() {
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            return Response.newBuilder().withContent("ok").build();
          }

          @Override
          public String id() {
            return "test-model";
          }

          @Override
          public String provider() {
            return "test";
          }
        };

    model.close();
    model.close();
  }

  @Test
  void modelIsAutoCloseable() {
    Model model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            return Response.newBuilder().withContent("ok").build();
          }

          @Override
          public String id() {
            return "test-model";
          }

          @Override
          public String provider() {
            return "test";
          }
        };

    assertTrue(model instanceof AutoCloseable);
    try (model) {
      assertEquals("test-model", model.id());
    }
  }

  @Test
  void closeableIteratorOfWrapsPlainIterator() {
    var plain = List.of("a", "b", "c").iterator();
    var closeable = CloseableIterator.of(plain);

    assertTrue(closeable.hasNext());
    assertEquals("a", closeable.next());
    assertEquals("b", closeable.next());
    assertEquals("c", closeable.next());
    assertFalse(closeable.hasNext());
    closeable.close(); // no-op, should not throw
  }
}
