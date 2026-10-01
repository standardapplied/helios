/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.Message;
import ai.singlr.core.model.ToolCall;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TokenCounterTest {

  private static final int PER_MESSAGE_OVERHEAD = 4;

  @Test
  void charBasedReturnsSharedInstance() {
    assertSame(TokenCounter.charBased(), TokenCounter.charBased());
    assertNotNull(TokenCounter.charBased());
  }

  @Test
  void emptyHistoryIsZeroTokens() {
    assertEquals(0L, TokenCounter.charBased().count(List.of()));
  }

  @Test
  void countNullHistoryThrows() {
    assertThrows(NullPointerException.class, () -> TokenCounter.charBased().count(null));
  }

  @Test
  void singleUserMessageRoundsUpAndAddsOverhead() {
    // "hello world" is 11 chars → ceil(11/4) = 3, + 4 overhead = 7
    assertEquals(7L, TokenCounter.charBased().count(List.of(Message.user("hello world"))));
  }

  @Test
  void exactlyFourCharsDoesNotRoundUp() {
    // "abcd" is 4 chars → ceil(4/4) = 1, + 4 overhead = 5
    assertEquals(5L, TokenCounter.charBased().count(List.of(Message.user("abcd"))));
  }

  @Test
  void emptyContentMessageHasOnlyOverhead() {
    // null content → 0 chars / 4 = 0, + 4 overhead = 4
    assertEquals(
        (long) PER_MESSAGE_OVERHEAD, TokenCounter.charBased().count(List.of(Message.user(""))));
  }

  @Test
  void multiMessageSumsAcrossHistory() {
    // each "a" is 1 char → ceil(1/4) = 1, + 4 = 5 per message; 3 messages = 15
    var history = List.of(Message.user("a"), Message.assistant("a"), Message.user("a"));
    assertEquals(15L, TokenCounter.charBased().count(history));
  }

  @Test
  void toolCallArgumentsAreCounted() {
    // ToolCall.id="t1" (2) + name="echo" (4) + key "msg" (3) + value "hi" (2) = 11 chars
    // ceil(11/4) = 3, + 4 overhead = 7
    var call = new ToolCall("t1", "echo", Map.of("msg", "hi"));
    var msg = Message.assistant(List.of(call));
    assertEquals(7L, TokenCounter.charBased().count(List.of(msg)));
  }

  @Test
  void toolResultMessageCountsToolCallIdAndName() {
    // content "ok" (2) + toolCallId "t1" (2) + toolName "echo" (4) = 8 chars
    // ceil(8/4) = 2, + 4 overhead = 6
    var msg = Message.tool("t1", "echo", "ok");
    assertEquals(6L, TokenCounter.charBased().count(List.of(msg)));
  }

  @Test
  void toolCallNullArgumentValueIsTolerated() {
    var args = new java.util.HashMap<String, Object>();
    args.put("k", null);
    var call = new ToolCall("id1", "n", args);
    var msg = Message.assistant(List.of(call));
    // chars: "id1"=3 + "n"=1 + "k"=1 + (null value contributes 0) = 5
    // ceil(5/4) = 2, + 4 overhead = 6
    assertEquals(6L, TokenCounter.charBased().count(List.of(msg)));
  }

  @Test
  void systemPromptIsCountedLikeAnyMessage() {
    // "you are a helpful assistant" = 27 chars → ceil(27/4) = 7, +4 = 11
    var sys = Message.system("you are a helpful assistant");
    assertEquals(11L, TokenCounter.charBased().count(List.of(sys)));
  }

  @Test
  void countIsMonotonicAsHistoryGrows() {
    var counter = TokenCounter.charBased();
    var smaller = List.of(Message.user("first"));
    var larger = List.of(Message.user("first"), Message.assistant("second"));
    assertTrue(counter.count(larger) > counter.count(smaller));
  }
}
