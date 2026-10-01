/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.repl.codeact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.ModelChunk;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.Role;
import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.core.tool.Tool;
import ai.singlr.repl.host.HostFunction;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Direct unit tests for {@link PredictFunction}. Validates argument shape ({@code instructions},
 * {@code input}), null/blank rejection, the contract that the sub-LM sees fresh-context system+user
 * messages, and that the response content is returned under the {@code "output"} key the in-sandbox
 * {@code predict(...)} wrapper expects.
 */
final class PredictFunctionTest {

  /** Fixed-reply sub-model that captures the last messages passed in. */
  private static final class CapturingModel implements Model {
    private final String reply;
    final AtomicReference<List<Message>> seen = new AtomicReference<>();

    CapturingModel(String reply) {
      this.reply = reply;
    }

    @Override
    public Response<Void> chat(List<Message> messages, List<Tool> tools) {
      seen.set(messages);
      return Response.newBuilder().withContent(reply).build();
    }

    @Override
    public Flow.Publisher<ModelChunk> chatStream(
        List<Message> messages, List<Tool> tools, CancellationToken cancellation) {
      throw new AssertionError("predict is supposed to call chat, not chatStream");
    }

    @Override
    public String id() {
      return "sub";
    }

    @Override
    public String provider() {
      return "test";
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> invoke(HostFunction fn, Map<String, Object> params) {
    try {
      return (Map<String, Object>) fn.handler().handle(params);
    } catch (Exception e) {
      throw new AssertionError("predict handler must not throw on valid input", e);
    }
  }

  @Test
  void reservedName() {
    assertEquals("predict", PredictFunction.NAME);
  }

  @Test
  void createRejectsNullSubModel() {
    var ex = assertThrows(NullPointerException.class, () -> PredictFunction.create(null));
    assertEquals("subModel must not be null", ex.getMessage());
  }

  @Test
  void parametersAreInstructionsAndInput() {
    var fn = PredictFunction.create(new CapturingModel(""));
    var names = fn.parameters().stream().map(p -> p.name()).toList();
    assertEquals(List.of("instructions", "input"), names);
    fn.parameters().forEach(p -> assertTrue(p.required()));
  }

  @Test
  void invokesSubModelAsSystemPlusUserMessages() {
    var model = new CapturingModel("widgets are great");
    var fn = PredictFunction.create(model);
    var out = invoke(fn, Map.of("instructions", "Summarize", "input", "Widgets are useful tools."));
    assertEquals("widgets are great", out.get("output"));
    var seen = model.seen.get();
    assertNotNull(seen);
    assertEquals(2, seen.size());
    assertEquals(Role.SYSTEM, seen.get(0).role());
    assertEquals("Summarize", seen.get(0).content());
    assertEquals(Role.USER, seen.get(1).role());
    assertEquals("Widgets are useful tools.", seen.get(1).content());
  }

  @Test
  void nullContentResponseReturnsEmptyString() {
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            return Response.newBuilder().withContent(null).build();
          }

          @Override
          public Flow.Publisher<ModelChunk> chatStream(
              List<Message> messages, List<Tool> tools, CancellationToken cancellation) {
            throw new AssertionError();
          }

          @Override
          public String id() {
            return "null-content";
          }

          @Override
          public String provider() {
            return "test";
          }
        };
    var fn = PredictFunction.create(model);
    var out = invoke(fn, Map.of("instructions", "x", "input", "y"));
    assertEquals("", out.get("output"));
  }

  @Test
  void blankInstructionsRejected() {
    var fn = PredictFunction.create(new CapturingModel(""));
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> invokeChecked(fn, Map.of("instructions", "   ", "input", "x")));
    assertTrue(ex.getMessage().contains("instructions"));
  }

  @Test
  void blankInputRejected() {
    var fn = PredictFunction.create(new CapturingModel(""));
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> invokeChecked(fn, Map.of("instructions", "x", "input", "")));
    assertTrue(ex.getMessage().contains("input"));
  }

  @Test
  void nonStringInstructionsRejected() {
    var fn = PredictFunction.create(new CapturingModel(""));
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> invokeChecked(fn, Map.of("instructions", 42, "input", "x")));
    assertTrue(ex.getMessage().contains("instructions"));
  }

  @Test
  void missingInputRejected() {
    var fn = PredictFunction.create(new CapturingModel(""));
    var paramsNoInput = Map.<String, Object>of("instructions", "x");
    var ex = assertThrows(IllegalArgumentException.class, () -> invokeChecked(fn, paramsNoInput));
    assertTrue(ex.getMessage().contains("input"));
  }

  @Test
  void modelInstanceIsTheOneCalled() {
    var model = new CapturingModel("reply");
    var fn = PredictFunction.create(model);
    invoke(fn, Map.of("instructions", "x", "input", "y"));
    assertSame(Role.SYSTEM, model.seen.get().get(0).role());
  }

  private static Object invokeChecked(HostFunction fn, Map<String, Object> params)
      throws Exception {
    return fn.handler().handle(params);
  }
}
