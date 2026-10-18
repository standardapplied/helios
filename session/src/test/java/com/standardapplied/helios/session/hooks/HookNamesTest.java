/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.session.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.UserMessage;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The default {@link Hook#name()} and {@link Hook#priority()} of each of the nine phase interfaces:
 * a named class is named by its simple name, an anonymous one by its binary name.
 */
final class HookNamesTest {

  private record PreToolUse() implements PreToolUseHook {
    @Override
    public HookOutcome beforeTool(ToolCall call, HookContext ctx) {
      return HookOutcome.cont();
    }
  }

  private record PostToolUse() implements PostToolUseHook {
    @Override
    public HookOutcome afterTool(ToolCall call, ToolResult result, HookContext ctx) {
      return HookOutcome.cont();
    }
  }

  private record PreModelTurn() implements PreModelTurnHook {
    @Override
    public HookOutcome beforeModelTurn(List<Message> history, HookContext ctx) {
      return HookOutcome.cont();
    }
  }

  private record PostModelTurn() implements PostModelTurnHook {
    @Override
    public HookOutcome afterModelTurn(Response<?> response, HookContext ctx) {
      return HookOutcome.cont();
    }
  }

  private record PreStop() implements PreStopHook {
    @Override
    public HookOutcome beforeStop(Response<?> stopResponse, HookContext ctx) {
      return HookOutcome.cont();
    }
  }

  private record PreCompact() implements PreCompactHook {
    @Override
    public HookOutcome beforeCompact(List<Message> history, HookContext ctx) {
      return HookOutcome.cont();
    }
  }

  private record PostCompact() implements PostCompactHook {
    @Override
    public HookOutcome afterCompact(CompactionPayload payload, HookContext ctx) {
      return HookOutcome.cont();
    }
  }

  private record OnUserMessage() implements OnUserMessageHook {
    @Override
    public HookOutcome onUserMessage(UserMessage msg, HookContext ctx) {
      return HookOutcome.cont();
    }
  }

  private record OnStreamEvent() implements OnStreamEventHook {
    @Override
    public void onEvent(QueryEvent event, HookContext ctx) {}
  }

  static Stream<Arguments> phases() {
    return Stream.of(
        Arguments.of(
            new PreToolUse(),
            "PreToolUse",
            new PreToolUseHook() {
              @Override
              public HookOutcome beforeTool(ToolCall call, HookContext ctx) {
                return HookOutcome.cont();
              }
            }),
        Arguments.of(
            new PostToolUse(),
            "PostToolUse",
            new PostToolUseHook() {
              @Override
              public HookOutcome afterTool(ToolCall call, ToolResult result, HookContext ctx) {
                return HookOutcome.cont();
              }
            }),
        Arguments.of(
            new PreModelTurn(),
            "PreModelTurn",
            new PreModelTurnHook() {
              @Override
              public HookOutcome beforeModelTurn(List<Message> history, HookContext ctx) {
                return HookOutcome.cont();
              }
            }),
        Arguments.of(
            new PostModelTurn(),
            "PostModelTurn",
            new PostModelTurnHook() {
              @Override
              public HookOutcome afterModelTurn(Response<?> response, HookContext ctx) {
                return HookOutcome.cont();
              }
            }),
        Arguments.of(
            new PreStop(),
            "PreStop",
            new PreStopHook() {
              @Override
              public HookOutcome beforeStop(Response<?> stopResponse, HookContext ctx) {
                return HookOutcome.cont();
              }
            }),
        Arguments.of(
            new PreCompact(),
            "PreCompact",
            new PreCompactHook() {
              @Override
              public HookOutcome beforeCompact(List<Message> history, HookContext ctx) {
                return HookOutcome.cont();
              }
            }),
        Arguments.of(
            new PostCompact(),
            "PostCompact",
            new PostCompactHook() {
              @Override
              public HookOutcome afterCompact(CompactionPayload payload, HookContext ctx) {
                return HookOutcome.cont();
              }
            }),
        Arguments.of(
            new OnUserMessage(),
            "OnUserMessage",
            new OnUserMessageHook() {
              @Override
              public HookOutcome onUserMessage(UserMessage msg, HookContext ctx) {
                return HookOutcome.cont();
              }
            }),
        Arguments.of(
            new OnStreamEvent(),
            "OnStreamEvent",
            new OnStreamEventHook() {
              @Override
              public void onEvent(QueryEvent event, HookContext ctx) {}
            }));
  }

  @ParameterizedTest(name = "{1}")
  @MethodSource("phases")
  void aNamedClassIsNamedBySimpleNameAndAnAnonymousOneByBinaryName(
      Hook named, String simpleName, Hook anonymous) {
    assertEquals(simpleName, named.name());
    assertEquals(100, named.priority());
    assertTrue(anonymous.getClass().isAnonymousClass(), anonymous.getClass().getName());
    assertEquals(anonymous.getClass().getName(), anonymous.name());
    assertTrue(anonymous.name().startsWith(HookNamesTest.class.getName() + "$"), anonymous.name());
  }
}
