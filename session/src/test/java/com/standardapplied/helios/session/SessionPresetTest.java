/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.core.common.CostCalculator;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.tool.Tool;
import ai.singlr.session.hooks.HookOutcome;
import ai.singlr.session.hooks.PreToolUseHook;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class SessionPresetTest {

  private static Model stubModel() {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        return Response.newBuilder().withContent("x").build();
      }

      @Override
      public String id() {
        return "stub";
      }

      @Override
      public String provider() {
        return "stub";
      }
    };
  }

  private static SessionOptions.Builder seed() {
    return SessionOptions.newBuilder().withModel(stubModel());
  }

  @Test
  void identityPresetReturnsBuilderUnchanged() {
    SessionPreset identity = b -> b;
    var builder = seed();
    var same = builder.apply(identity);
    assertSame(builder, same);
    var opts = same.build();
    assertNotNull(opts);
    assertEquals(0, opts.tools().bindings().size());
  }

  @Test
  void applyInvokesPresetExactlyOnce() {
    var hits = new AtomicInteger();
    SessionPreset counter =
        b -> {
          hits.incrementAndGet();
          return b;
        };
    seed().apply(counter).build();
    assertEquals(1, hits.get());
  }

  @Test
  void applyRejectsNullPreset() {
    var b = seed();
    var ex = assertThrows(NullPointerException.class, () -> b.apply(null));
    assertEquals("preset must not be null", ex.getMessage());
  }

  @Test
  void applyRejectsNullBuilderReturnedByPreset() {
    SessionPreset badPreset = b -> null;
    var b = seed();
    var ex = assertThrows(NullPointerException.class, () -> b.apply(badPreset));
    assertEquals("preset must return a non-null builder", ex.getMessage());
  }

  @Test
  void presetCanLayerConfiguration() {
    SessionPreset preset = b -> b.withSessionId("preset-id");
    var opts = seed().apply(preset).build();
    assertEquals("preset-id", opts.sessionId());
  }

  @Test
  void presetsStackAssociatively() {
    SessionPreset one = b -> b.withSessionId("first");
    SessionPreset two = b -> b.withSessionId("second");
    var opts = seed().apply(one).apply(two).build();
    assertEquals("second", opts.sessionId());
  }

  @Test
  void presetsLayerDistinctFields() {
    SessionPreset tracing = b -> b.withSessionId("trace");
    SessionPreset costing = b -> b.withCostCalculator(CostCalculator.staticTable(Map.of()));
    var opts = seed().apply(tracing).apply(costing).build();
    assertEquals("trace", opts.sessionId());
    assertSame(CostCalculator.staticTable(Map.of()).getClass(), opts.costCalculator().getClass());
  }

  @Test
  void presetCanAppendHook() {
    PreToolUseHook hook = (call, ctx) -> HookOutcome.cont();
    SessionPreset preset = b -> b.withHook(hook);
    var opts = seed().apply(preset).build();
    assertEquals(1, opts.hooks().size());
    assertSame(hook, opts.hooks().get(0));
  }

  @Test
  void presetCanReturnDifferentBuilderInstance() {
    var replacement = SessionOptions.newBuilder().withModel(stubModel()).withSessionId("forked");
    SessionPreset preset = b -> replacement;
    var opts = seed().apply(preset).build();
    assertEquals("forked", opts.sessionId());
  }

  @Test
  void presetIsFunctionalInterfaceUsableAsMethodReference() {
    SessionPreset preset = SessionPresetTest::tagAsCanonical;
    var opts = seed().apply(preset).build();
    assertEquals("canonical", opts.sessionId());
  }

  private static SessionOptions.Builder tagAsCanonical(SessionOptions.Builder b) {
    return b.withSessionId("canonical");
  }
}
