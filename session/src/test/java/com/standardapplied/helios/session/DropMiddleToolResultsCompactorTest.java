/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.Response.Usage;
import ai.singlr.core.model.Role;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.core.tool.Tool;
import ai.singlr.session.loop.SessionState;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class DropMiddleToolResultsCompactorTest {

  private static Model summaryModel(String content) {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        return Response.newBuilder().withContent(content).build();
      }

      @Override
      public String id() {
        return "summary";
      }

      @Override
      public String provider() {
        return "summary";
      }
    };
  }

  private static SessionState freshState() {
    return new SessionState("sess-1", new CancellationToken(), Clock.systemUTC());
  }

  private static List<Message> chain(int n) {
    var out = new ArrayList<Message>(n);
    for (var i = 0; i < n; i++) {
      out.add(i % 2 == 0 ? Message.user("u-" + i) : Message.assistant("a-" + i));
    }
    return List.copyOf(out);
  }

  @Test
  void newBuilderRejectsNullSummaryModel() {
    assertThrows(NullPointerException.class, () -> DropMiddleToolResultsCompactor.newBuilder(null));
  }

  @Test
  void defaultsExposedViaAccessors() {
    var model = summaryModel("ignored");
    var compactor = DropMiddleToolResultsCompactor.newBuilder(model).build();
    assertSame(model, compactor.summaryModel());
    assertEquals(3, compactor.headPreserved());
    assertEquals(20, compactor.tailPreserved());
  }

  @Test
  void withHeadPreservedRejectsZero() {
    var b = DropMiddleToolResultsCompactor.newBuilder(summaryModel("x"));
    assertThrows(IllegalArgumentException.class, () -> b.withHeadPreserved(0));
  }

  @Test
  void withTailPreservedRejectsNegative() {
    var b = DropMiddleToolResultsCompactor.newBuilder(summaryModel("x"));
    assertThrows(IllegalArgumentException.class, () -> b.withTailPreserved(-1));
  }

  @Test
  void withSummaryPromptRejectsNull() {
    var b = DropMiddleToolResultsCompactor.newBuilder(summaryModel("x"));
    assertThrows(NullPointerException.class, () -> b.withSummaryPrompt(null));
  }

  @Test
  void withSummaryPromptRejectsBlank() {
    var b = DropMiddleToolResultsCompactor.newBuilder(summaryModel("x"));
    assertThrows(IllegalArgumentException.class, () -> b.withSummaryPrompt("   "));
  }

  @Test
  void shorterThanHeadPlusTailReturnsHistoryUnchanged() {
    var compactor =
        DropMiddleToolResultsCompactor.newBuilder(summaryModel("ignored"))
            .withHeadPreserved(3)
            .withTailPreserved(5)
            .build();
    var history = chain(7);
    var out = compactor.compact(history, freshState());
    assertSame(history, out.history(), "no compaction expected when size <= head + tail");
    assertEquals(0, out.usage().inputTokens());
    assertEquals(0, out.usage().outputTokens());
  }

  @Test
  void exactHeadPlusTailReturnsHistoryUnchanged() {
    var compactor =
        DropMiddleToolResultsCompactor.newBuilder(summaryModel("ignored"))
            .withHeadPreserved(2)
            .withTailPreserved(2)
            .build();
    var history = chain(4);
    assertSame(history, compactor.compact(history, freshState()).history());
  }

  @Test
  void longerThanHeadPlusTailReplacesMiddleWithSummary() {
    var summaryCalls = new AtomicInteger(0);
    Model summarizer =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            summaryCalls.incrementAndGet();
            return Response.newBuilder().withContent("the gist").build();
          }

          @Override
          public String id() {
            return "summary";
          }

          @Override
          public String provider() {
            return "summary";
          }
        };
    var compactor =
        DropMiddleToolResultsCompactor.newBuilder(summarizer)
            .withHeadPreserved(2)
            .withTailPreserved(2)
            .build();
    var history = chain(10);
    var out = compactor.compact(history, freshState()).history();
    assertEquals(2 + 1 + 2, out.size(), "head + 1 summary + tail");
    assertEquals(1, summaryCalls.get(), "summarizer called exactly once");
    // The first two head messages are preserved exactly.
    assertEquals(history.get(0).content(), out.get(0).content());
    assertEquals(history.get(1).content(), out.get(1).content());
    // The middle is replaced with a summary user message prefixed with the marker.
    assertEquals(ai.singlr.core.model.Role.USER, out.get(2).role());
    assertTrue(out.get(2).content().startsWith("[Earlier context summary]"));
    assertTrue(out.get(2).content().contains("the gist"));
    // The last two tail messages are preserved exactly.
    assertEquals(history.get(8).content(), out.get(3).content());
    assertEquals(history.get(9).content(), out.get(4).content());
  }

  @Test
  void summarizerThrowingReturnsOriginalHistory() {
    Model thrower =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new RuntimeException("boom");
          }

          @Override
          public String id() {
            return "summary";
          }

          @Override
          public String provider() {
            return "summary";
          }
        };
    var compactor =
        DropMiddleToolResultsCompactor.newBuilder(thrower)
            .withHeadPreserved(1)
            .withTailPreserved(1)
            .build();
    var history = chain(10);
    var out = compactor.compact(history, freshState());
    assertSame(history, out.history(), "throwing summarizer must yield no-op");
    assertEquals(0, out.usage().inputTokens());
  }

  @Test
  void summarizerReturningBlankReturnsOriginalHistory() {
    var compactor =
        DropMiddleToolResultsCompactor.newBuilder(summaryModel(""))
            .withHeadPreserved(1)
            .withTailPreserved(1)
            .build();
    var history = chain(10);
    assertSame(history, compactor.compact(history, freshState()).history());
  }

  @Test
  void summarizerReturningNullContentReturnsOriginalHistory() {
    Model nullContent =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            return Response.newBuilder().build();
          }

          @Override
          public String id() {
            return "summary";
          }

          @Override
          public String provider() {
            return "summary";
          }
        };
    var compactor =
        DropMiddleToolResultsCompactor.newBuilder(nullContent)
            .withHeadPreserved(1)
            .withTailPreserved(1)
            .build();
    var history = chain(10);
    assertSame(history, compactor.compact(history, freshState()).history());
  }

  @Test
  void compactRejectsNullHistory() {
    var compactor = DropMiddleToolResultsCompactor.newBuilder(summaryModel("x")).build();
    assertThrows(NullPointerException.class, () -> compactor.compact(null, freshState()));
  }

  @Test
  void compactRejectsNullState() {
    var compactor = DropMiddleToolResultsCompactor.newBuilder(summaryModel("x")).build();
    assertThrows(NullPointerException.class, () -> compactor.compact(List.of(), null));
  }

  @Test
  void customSummaryPromptUsedInSummaryRequest() {
    var observed = new ArrayList<String>();
    Model capturing =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            for (var m : messages) {
              if (m.role() == ai.singlr.core.model.Role.SYSTEM) {
                observed.add(m.content());
              }
            }
            return Response.newBuilder().withContent("done").build();
          }

          @Override
          public String id() {
            return "summary";
          }

          @Override
          public String provider() {
            return "summary";
          }
        };
    var custom = "strict summary prompt";
    var compactor =
        DropMiddleToolResultsCompactor.newBuilder(capturing)
            .withHeadPreserved(1)
            .withTailPreserved(1)
            .withSummaryPrompt(custom)
            .build();
    compactor.compact(chain(8), freshState());
    assertTrue(observed.contains(custom), "custom prompt must flow through to the summarizer");
  }

  @Test
  void disabledCompactorReturnsHistoryUnchanged() {
    var disabled = ContextCompactor.disabled();
    var history = chain(50);
    var out = disabled.compact(history, freshState());
    assertSame(history, out.history());
    assertEquals(0, out.usage().inputTokens());
    assertEquals(0, out.usage().outputTokens());
  }

  @Test
  void disabledCompactorRejectsNullArgs() {
    var disabled = ContextCompactor.disabled();
    assertThrows(NullPointerException.class, () -> disabled.compact(null, freshState()));
    assertThrows(NullPointerException.class, () -> disabled.compact(List.of(), null));
  }

  @Test
  void disabledIsASingleton() {
    assertSame(ContextCompactor.disabled(), ContextCompactor.disabled());
    assertNotNull(ContextCompactor.disabled());
  }

  // ── boundary alignment for tool_call / tool_result pairs ─────────────────

  /**
   * Build a history with a tool_call/tool_result pair STRADDLING the requested head boundary:
   *
   * <pre>
   *   index 0: user "go"
   *   index 1: user "again"
   *   index 2: assistant tool_call(id=t1)        ← if head=3, this is the last head message
   *   index 3: tool result(t1)                   ← would be orphaned in the middle
   *   index 4..11: user/assistant chatter (middle)
   *   index 12..15: tail
   * </pre>
   */
  @Test
  void adjustHeadAdvancesPastToolCallResultPair() {
    var history = new ArrayList<Message>();
    history.add(Message.user("go"));
    history.add(Message.user("again"));
    history.add(Message.assistant(List.of(new ToolCall("t1", "echo", Map.of()))));
    history.add(Message.tool("t1", "echo", "result1"));
    for (var i = 4; i < 16; i++) {
      history.add(i % 2 == 0 ? Message.user("u-" + i) : Message.assistant("a-" + i));
    }
    var safe = DropMiddleToolResultsCompactor.adjustHead(List.copyOf(history), 3);
    // proposed head was 3 (would split the t1 pair); safe head should be 4 (includes the result).
    assertEquals(4, safe);
  }

  @Test
  void adjustHeadAcceptsProposedBoundaryWhenNoToolCallsPending() {
    var history = chain(10);
    assertEquals(3, DropMiddleToolResultsCompactor.adjustHead(history, 3));
  }

  @Test
  void adjustHeadReturnsMinusOneWhenNoSafeBoundaryExists() {
    // history: assistant(tc1), assistant(tc2), tool(t1)
    // pending evolves: {t1} → {t1,t2} → {t2}. Never empties past proposed=1, so no safe cut.
    var history = new ArrayList<Message>();
    history.add(Message.assistant(List.of(new ToolCall("t1", "echo", Map.of()))));
    history.add(Message.assistant(List.of(new ToolCall("t2", "echo", Map.of()))));
    history.add(Message.tool("t1", "echo", "r1"));
    assertEquals(-1, DropMiddleToolResultsCompactor.adjustHead(List.copyOf(history), 1));
  }

  /**
   * Build a history with a tool_call/tool_result pair STRADDLING the requested tail boundary:
   *
   * <pre>
   *   index 0..9: head + middle
   *   index 10: assistant tool_call(id=t2)       ← in middle, would be dropped
   *   index 11: tool result(t2)                  ← if tail starts here, this is orphaned
   *   index 12..14: tail
   * </pre>
   */
  @Test
  void adjustTailStartShiftsBackwardToKeepToolCallPairTogether() {
    var history = new ArrayList<Message>();
    for (var i = 0; i < 10; i++) {
      history.add(i % 2 == 0 ? Message.user("u-" + i) : Message.assistant("a-" + i));
    }
    history.add(Message.assistant(List.of(new ToolCall("t2", "echo", Map.of()))));
    history.add(Message.tool("t2", "echo", "result2"));
    for (var i = 12; i < 15; i++) {
      history.add(i % 2 == 0 ? Message.user("u-" + i) : Message.assistant("a-" + i));
    }
    // Proposed tail of 4 starts at index 11 (the orphan tool message). Safe shift goes to 10
    // so the assistant tool_call stays with its result.
    var safe = DropMiddleToolResultsCompactor.adjustTailStart(List.copyOf(history), 11);
    assertEquals(10, safe);
  }

  @Test
  void compactReturnsNoOpWhenBoundaryAlignmentLeavesNoMiddle() {
    // Head must absorb the entire unresolved tool-call sequence; tail starts right after.
    // history: assistant(tc1), tool(t1), assistant(tc2) [no result] — only safe head is k=2 (after
    // first pair) or none. With head=1 + tail=1, after alignment we expect headCut >= tailStart
    // → no middle → CompactionResult.noOp.
    var history = new ArrayList<Message>();
    history.add(Message.assistant(List.of(new ToolCall("t1", "echo", Map.of()))));
    history.add(Message.tool("t1", "echo", "r1"));
    history.add(Message.assistant(List.of(new ToolCall("t2", "echo", Map.of()))));
    var summaryCalls = new AtomicInteger(0);
    var compactor =
        DropMiddleToolResultsCompactor.newBuilder(
                new Model() {
                  @Override
                  public Response<Void> chat(List<Message> messages, List<Tool> tools) {
                    summaryCalls.incrementAndGet();
                    return Response.newBuilder().withContent("never").build();
                  }

                  @Override
                  public String id() {
                    return "summary";
                  }

                  @Override
                  public String provider() {
                    return "summary";
                  }
                })
            .withHeadPreserved(1)
            .withTailPreserved(1)
            .build();
    var input = List.copyOf(history);
    var out = compactor.compact(input, freshState());
    assertSame(input, out.history(), "boundary alignment ate the middle → no-op");
    assertEquals(0, summaryCalls.get(), "summarizer must not be called when middle is empty");
    assertEquals(0, out.usage().inputTokens());
  }

  @Test
  void compactRespectsToolCallBoundaryAtHeadAndTail() {
    // history: user, assistant(tc1), tool(t1), [middle: 10 chatter rows], assistant(tc2), tool(t2),
    // user, assistant
    var history = new ArrayList<Message>();
    history.add(Message.user("opening"));
    history.add(Message.assistant(List.of(new ToolCall("t1", "echo", Map.of()))));
    history.add(Message.tool("t1", "echo", "r1"));
    for (var i = 3; i < 13; i++) {
      history.add(i % 2 == 1 ? Message.user("u-" + i) : Message.assistant("a-" + i));
    }
    history.add(Message.assistant(List.of(new ToolCall("t2", "echo", Map.of()))));
    history.add(Message.tool("t2", "echo", "r2"));
    history.add(Message.user("recent"));
    history.add(Message.assistant("recent answer"));
    var compactor =
        DropMiddleToolResultsCompactor.newBuilder(summaryModel("the gist"))
            .withHeadPreserved(2)
            .withTailPreserved(3)
            .build();
    var out = compactor.compact(List.copyOf(history), freshState()).history();
    // Verify no orphans: every TOOL message in out has a matching tool_call earlier in out.
    var seenIds = new java.util.HashSet<String>();
    for (var m : out) {
      if (m.role() == Role.ASSISTANT && m.hasToolCalls()) {
        for (var c : m.toolCalls()) {
          seenIds.add(c.id());
        }
      } else if (m.role() == Role.TOOL) {
        assertTrue(
            seenIds.contains(m.toolCallId()),
            "TOOL message with id " + m.toolCallId() + " has no preceding tool_call");
      }
    }
  }

  // ── summary call timeout + usage reporting ───────────────────────────────

  @Test
  void summaryUsageReportedOnCompactionResult() {
    Model summarizer =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            return Response.newBuilder()
                .withContent("summary")
                .withUsage(Usage.of(150, 25))
                .build();
          }

          @Override
          public String id() {
            return "summary";
          }

          @Override
          public String provider() {
            return "summary";
          }
        };
    var compactor =
        DropMiddleToolResultsCompactor.newBuilder(summarizer)
            .withHeadPreserved(1)
            .withTailPreserved(1)
            .build();
    var out = compactor.compact(chain(10), freshState());
    assertEquals(150, out.usage().inputTokens());
    assertEquals(25, out.usage().outputTokens());
    assertEquals(
        "summary",
        out.modelId(),
        "CompactionResult must carry the summary model id so the loop prices spend at its rate");
  }

  @Test
  void summaryTimeoutLeavesHistoryUnchanged() {
    var inFlight = new CountDownLatch(1);
    Model slow =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            try {
              // Hang well past the configured timeout to force expiration.
              inFlight.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            return Response.newBuilder().withContent("never").build();
          }

          @Override
          public String id() {
            return "summary";
          }

          @Override
          public String provider() {
            return "summary";
          }
        };
    var compactor =
        DropMiddleToolResultsCompactor.newBuilder(slow)
            .withHeadPreserved(1)
            .withTailPreserved(1)
            .withSummaryTimeout(Duration.ofMillis(100))
            .build();
    var history = chain(10);
    var out = compactor.compact(history, freshState());
    assertSame(history, out.history(), "timeout must yield no-op");
    assertEquals(0, out.usage().inputTokens());
    inFlight.countDown();
  }

  @Test
  void summaryTimeoutDefaultsToSixtySeconds() {
    var compactor = DropMiddleToolResultsCompactor.newBuilder(summaryModel("x")).build();
    assertEquals(Duration.ofSeconds(60), compactor.summaryTimeout());
  }

  @Test
  void withSummaryTimeoutRejectsNull() {
    var b = DropMiddleToolResultsCompactor.newBuilder(summaryModel("x"));
    assertThrows(NullPointerException.class, () -> b.withSummaryTimeout(null));
  }

  @Test
  void withSummaryTimeoutRejectsZero() {
    var b = DropMiddleToolResultsCompactor.newBuilder(summaryModel("x"));
    assertThrows(IllegalArgumentException.class, () -> b.withSummaryTimeout(Duration.ZERO));
  }

  @Test
  void withSummaryTimeoutRejectsNegative() {
    var b = DropMiddleToolResultsCompactor.newBuilder(summaryModel("x"));
    assertThrows(
        IllegalArgumentException.class, () -> b.withSummaryTimeout(Duration.ofSeconds(-1)));
  }

  @Test
  void compactionResultNoOpHasZeroUsageAndBlankModelId() {
    var history = chain(5);
    var result = CompactionResult.noOp(history);
    assertSame(history, result.history());
    assertEquals(0, result.usage().inputTokens());
    assertEquals(0, result.usage().outputTokens());
    assertEquals("", result.modelId(), "noOp uses an empty modelId — no spend to attribute");
  }

  @Test
  void compactionResultRejectsNullArgs() {
    assertThrows(NullPointerException.class, () -> new CompactionResult(null, Usage.of(0, 0), ""));
    assertThrows(NullPointerException.class, () -> new CompactionResult(List.of(), null, ""));
    assertThrows(
        NullPointerException.class, () -> new CompactionResult(List.of(), Usage.of(0, 0), null));
  }

  @Test
  void compactionResultRejectsUsageWithoutModelId() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new CompactionResult(List.of(), Usage.of(10, 5), ""));
    assertTrue(
        ex.getMessage().startsWith("modelId must be non-blank when usage reports any tokens"),
        ex.getMessage());
  }
}
