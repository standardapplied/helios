/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.repl.codeact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.ToolCall;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.session.hooks.HookContext;
import ai.singlr.session.hooks.HookOutcome;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Direct unit tests for {@link OnSubmitStopHook}. Validates the post-tool hook contract: when the
 * holder is populated, the hook returns {@link HookOutcome.Stop} carrying the JSON-serialized
 * value; otherwise it returns {@link HookOutcome.Continue}.
 */
final class OnSubmitStopHookTest {

  public record Answer(String value, int count) {}

  private static ToolCall call() {
    return new ToolCall("c1", "submit", Map.of("output", Map.of()));
  }

  private static ToolResult ok() {
    return ToolResult.success("ok");
  }

  @Test
  void rejectsNullHolder() {
    var ex = assertThrows(NullPointerException.class, () -> new OnSubmitStopHook(null));
    assertEquals("holder must not be null", ex.getMessage());
  }

  @Test
  void hookHasStableName() {
    var hook = new OnSubmitStopHook(new SubmittedValueHolder());
    assertEquals("OnSubmitStopHook", hook.name());
  }

  @Test
  void unpopulatedHolderContinues() {
    var hook = new OnSubmitStopHook(new SubmittedValueHolder());
    var outcome = hook.afterTool(call(), ok(), (HookContext) null);
    assertInstanceOf(HookOutcome.Continue.class, outcome);
  }

  @Test
  void populatedHolderStopsWithJsonSerialization() {
    var holder = new SubmittedValueHolder();
    holder.submit(new Answer("widgets", 3));
    var hook = new OnSubmitStopHook(holder);
    var outcome = hook.afterTool(call(), ok(), (HookContext) null);
    var stop = assertInstanceOf(HookOutcome.Stop.class, outcome);
    var json = stop.result();
    assertTrue(json.contains("\"value\""));
    assertTrue(json.contains("widgets"));
    assertTrue(json.contains("\"count\""));
    assertTrue(json.contains("3"));
  }

  @Test
  void mapValueAlsoStops() {
    var holder = new SubmittedValueHolder();
    holder.submit(Map.of("headline", "hi"));
    var hook = new OnSubmitStopHook(holder);
    var outcome = hook.afterTool(call(), ok(), (HookContext) null);
    var stop = assertInstanceOf(HookOutcome.Stop.class, outcome);
    assertTrue(stop.result().contains("headline"));
  }
}
