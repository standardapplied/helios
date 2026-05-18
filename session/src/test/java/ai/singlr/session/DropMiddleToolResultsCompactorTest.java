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
import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.core.tool.Tool;
import ai.singlr.session.loop.SessionState;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
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
    assertSame(history, out, "no compaction expected when size <= head + tail");
  }

  @Test
  void exactHeadPlusTailReturnsHistoryUnchanged() {
    var compactor =
        DropMiddleToolResultsCompactor.newBuilder(summaryModel("ignored"))
            .withHeadPreserved(2)
            .withTailPreserved(2)
            .build();
    var history = chain(4);
    assertSame(history, compactor.compact(history, freshState()));
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
    var out = compactor.compact(history, freshState());
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
    assertSame(history, out, "throwing summarizer must yield no-op");
  }

  @Test
  void summarizerReturningBlankReturnsOriginalHistory() {
    var compactor =
        DropMiddleToolResultsCompactor.newBuilder(summaryModel(""))
            .withHeadPreserved(1)
            .withTailPreserved(1)
            .build();
    var history = chain(10);
    assertSame(history, compactor.compact(history, freshState()));
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
    assertSame(history, compactor.compact(history, freshState()));
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
    assertSame(history, disabled.compact(history, freshState()));
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
}
