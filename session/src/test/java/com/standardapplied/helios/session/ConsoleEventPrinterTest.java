/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.model.Citation;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.tool.ToolResult;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

final class ConsoleEventPrinterTest {

  private static final String SID = "sess-1";
  private static final long TURN = 2;
  private static final Instant TS = Instant.parse("2026-10-05T00:00:00Z");
  private static final ToolCall CALL = new ToolCall("c1", "Read", Map.of("path", "a.txt"));

  private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
  private final ConsoleEventPrinter printer =
      new ConsoleEventPrinter(new PrintStream(bytes, false, StandardCharsets.UTF_8));

  private String printed() {
    return bytes.toString(StandardCharsets.UTF_8);
  }

  @Test
  void writesOneLinePerPrintedEventKind() {
    printer.onNext(
        new QueryEvent.AssistantCitations(
            SID,
            TURN,
            TS,
            List.of(
                Citation.newBuilder().withSourceId("s1").withTitle("Docs").build(),
                Citation.of("s2", "body"))));
    printer.onNext(new QueryEvent.ToolUse(SID, TURN, TS, CALL));
    printer.onNext(new QueryEvent.ToolResult(SID, TURN, TS, CALL, ToolResult.success("x")));
    printer.onNext(new QueryEvent.ToolResult(SID, TURN, TS, CALL, ToolResult.failure("nope")));
    printer.onNext(new QueryEvent.ToolBlocked(SID, TURN, TS, CALL, "perm", "denied"));
    printer.onNext(new QueryEvent.TurnEnded(SID, TURN, TS, StopReason.END_TURN));
    printer.onNext(
        new QueryEvent.LoopEnded(
            SID,
            TURN,
            TS,
            new ResultMessage.Success(
                SID, "done", Usage.of(1, 1), CostEstimate.zero(), Duration.ZERO, List.of())));

    var nl = System.lineSeparator();
    assertEquals(
        "\n[citations] [Docs, s2]"
            + nl
            + "\n[tool] Read {path=a.txt}"
            + nl
            + "[result] Read ok"
            + nl
            + "[result] Read FAILED: nope"
            + nl
            + "[blocked] Read: denied"
            + nl
            + "\n[turn-ended] END_TURN"
            + nl
            + "[loop-ended] Success"
            + nl,
        printed());
  }

  @Test
  void assistantTextIsFlushedAsItArrivesWithoutANewline() {
    var flushed = new ArrayList<String>();
    var sink = new ByteArrayOutputStream();
    var recording =
        new OutputStream() {
          @Override
          public void write(int b) {
            sink.write(b);
          }

          @Override
          public void flush() {
            flushed.add(sink.toString(StandardCharsets.UTF_8));
          }
        };
    var streaming =
        new ConsoleEventPrinter(new PrintStream(recording, false, StandardCharsets.UTF_8));

    streaming.onNext(new QueryEvent.AssistantText(SID, TURN, TS, "Hel"));
    streaming.onNext(new QueryEvent.AssistantText(SID, TURN, TS, "lo"));

    assertEquals(List.of("Hel", "Hello"), flushed);
  }

  @Test
  void longToolArgumentsAreCutWithTheirLengthNoted() {
    var arguments = "x".repeat(241);
    printer.onNext(
        new QueryEvent.ToolUse(SID, TURN, TS, new ToolCall("c2", "Grep", Map.of("p", arguments))));

    var rendered = Map.of("p", arguments).toString();
    assertEquals(
        "\n[tool] Grep "
            + rendered.substring(0, 240)
            + "... ["
            + rendered.length()
            + " chars]"
            + System.lineSeparator(),
        printed());
  }

  @Test
  void eventsWithoutALineAreSkipped() {
    printer.onNext(new QueryEvent.AssistantThinking(SID, TURN, TS, "hmm", ""));
    printer.onNext(new QueryEvent.ContextWarning(SID, TURN, TS, 0.9));

    assertEquals("", printed());
  }

  @Test
  void aStreamErrorIsOneLine() {
    printer.onError(new IllegalStateException("boom"));

    assertEquals(
        "[stream-error] java.lang.IllegalStateException: boom" + System.lineSeparator(), printed());
  }

  @Test
  void subscribingRequestsEveryEventAndCompletionFlushes() {
    var requested = new ArrayList<Long>();
    printer.onSubscribe(
        new Flow.Subscription() {
          @Override
          public void request(long n) {
            requested.add(n);
          }

          @Override
          public void cancel() {}
        });
    printer.onNext(new QueryEvent.AssistantText(SID, TURN, TS, "tail"));
    printer.onComplete();

    assertEquals(List.of(Long.MAX_VALUE), requested);
    assertEquals("tail", printed());
  }

  @Test
  void rejectsANullStream() {
    var ex = assertThrows(NullPointerException.class, () -> new ConsoleEventPrinter(null));
    assertEquals("out must not be null", ex.getMessage());
  }
}
