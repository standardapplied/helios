/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.repl.codeact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolContext;
import ai.singlr.session.tools.ToolCategory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class PredictToolTest {

  private static Model fixedModel(String reply) {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        return Response.newBuilder().withContent(reply).build();
      }

      @Override
      public String id() {
        return "fixed";
      }

      @Override
      public String provider() {
        return "test";
      }
    };
  }

  private static Model capturingModel(AtomicReference<List<Message>> capture, String reply) {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        capture.set(List.copyOf(messages));
        return Response.newBuilder().withContent(reply).build();
      }

      @Override
      public String id() {
        return "capturing";
      }

      @Override
      public String provider() {
        return "test";
      }
    };
  }

  private static Model throwingModel(RuntimeException e) {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        throw e;
      }

      @Override
      public String id() {
        return "throwing";
      }

      @Override
      public String provider() {
        return "test";
      }
    };
  }

  // ── construction ────────────────────────────────────────────────────────

  @Test
  void rejectsNullSubModel() {
    var ex = assertThrows(NullPointerException.class, () -> PredictTool.create(null));
    assertEquals("subModel must not be null", ex.getMessage());
  }

  @Test
  void toolHasStableNameAndTwoStringParameters() {
    var tool = PredictTool.create(fixedModel("ok"));
    assertEquals(PredictTool.NAME, tool.name());
    assertEquals(2, tool.parameters().size());
    assertEquals("instructions", tool.parameters().get(0).name());
    assertEquals("input", tool.parameters().get(1).name());
    assertTrue(tool.parameters().get(0).required());
    assertTrue(tool.parameters().get(1).required());
  }

  @Test
  void bindingCategorisesAsDelegation() {
    var binding = PredictTool.binding(fixedModel("ok"));
    assertSame(ToolCategory.DELEGATION, binding.category());
    assertEquals(PredictTool.NAME, binding.tool().name());
  }

  // ── happy path ──────────────────────────────────────────────────────────

  @Test
  void returnsSubModelContentOnSuccess() {
    var tool = PredictTool.create(fixedModel("sub reply"));
    var result =
        tool.execute(
            Map.of("instructions", "be terse", "input", "what is 2+2?"), ToolContext.noop());
    assertTrue(result.success());
    assertEquals("sub reply", result.output());
  }

  @Test
  void sendsExactlyTwoMessagesSystemThenUser() {
    var capture = new AtomicReference<List<Message>>(new ArrayList<>());
    var tool = PredictTool.create(capturingModel(capture, "ok"));
    tool.execute(Map.of("instructions", "INST", "input", "INP"), ToolContext.noop());
    var messages = capture.get();
    assertEquals(2, messages.size());
    assertEquals("system", messages.get(0).role().name().toLowerCase());
    assertEquals("user", messages.get(1).role().name().toLowerCase());
    assertEquals("INST", messages.get(0).content());
    assertEquals("INP", messages.get(1).content());
  }

  @Test
  void nullContentBecomesEmptyString() {
    Model nullContent =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            return Response.newBuilder().build();
          }

          @Override
          public String id() {
            return "n";
          }

          @Override
          public String provider() {
            return "n";
          }
        };
    var result =
        PredictTool.create(nullContent)
            .execute(Map.of("instructions", "i", "input", "x"), ToolContext.noop());
    assertTrue(result.success());
    assertEquals("", result.output());
  }

  // ── argument validation ─────────────────────────────────────────────────

  @Test
  void missingInstructionsFails() {
    var result =
        PredictTool.create(fixedModel("x")).execute(Map.of("input", "hi"), ToolContext.noop());
    assertFalse(result.success());
    assertTrue(result.output().contains("'instructions' is required"));
  }

  @Test
  void blankInstructionsFails() {
    var result =
        PredictTool.create(fixedModel("x"))
            .execute(Map.of("instructions", "   ", "input", "hi"), ToolContext.noop());
    assertFalse(result.success());
    assertTrue(result.output().contains("'instructions' is required"));
  }

  @Test
  void nonStringInstructionsFails() {
    var result =
        PredictTool.create(fixedModel("x"))
            .execute(Map.of("instructions", 42, "input", "hi"), ToolContext.noop());
    assertFalse(result.success());
    assertTrue(result.output().contains("'instructions' is required"));
  }

  @Test
  void missingInputFails() {
    var result =
        PredictTool.create(fixedModel("x"))
            .execute(Map.of("instructions", "be terse"), ToolContext.noop());
    assertFalse(result.success());
    assertTrue(result.output().contains("'input' is required"));
  }

  @Test
  void blankInputFails() {
    var result =
        PredictTool.create(fixedModel("x"))
            .execute(Map.of("instructions", "be terse", "input", " "), ToolContext.noop());
    assertFalse(result.success());
    assertTrue(result.output().contains("'input' is required"));
  }

  // ── error path ──────────────────────────────────────────────────────────

  @Test
  void subModelExceptionBecomesToolFailure() {
    var tool = PredictTool.create(throwingModel(new IllegalStateException("rate-limited")));
    var result = tool.execute(Map.of("instructions", "i", "input", "x"), ToolContext.noop());
    assertFalse(result.success());
    var msg = result.output();
    assertTrue(msg.contains("IllegalStateException"));
    assertTrue(msg.contains("rate-limited"));
  }

  @Test
  void subModelExceptionWithNullMessageHandledGracefully() {
    var tool = PredictTool.create(throwingModel(new RuntimeException((String) null)));
    var result = tool.execute(Map.of("instructions", "i", "input", "x"), ToolContext.noop());
    assertFalse(result.success());
    assertTrue(result.output().contains("<no message>"));
  }
}
