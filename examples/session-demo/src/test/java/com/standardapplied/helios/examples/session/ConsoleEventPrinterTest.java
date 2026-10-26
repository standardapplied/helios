/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.examples.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.model.Citation;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.ResultMessage;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ConsoleEventPrinterTest {

  private static final String SID = "sess-1";
  private static final long TURN = 2;
  private static final Instant TS = Instant.parse("2026-10-05T00:00:00Z");
  private static final ToolCall CALL = new ToolCall("c1", "Read", Map.of("path", "a.txt"));

  private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
  private final ConsoleEventPrinter printer =
      new ConsoleEventPrinter(new PrintStream(bytes, false, StandardCharsets.UTF_8));

  private void print(QueryEvent event) {
    printer.print(List.of(event));
  }

  private String printed() {
    return bytes.toString(StandardCharsets.UTF_8);
  }

  @Test
  void writesOneLinePerPrintedEventKind() {
    print(
        new QueryEvent.AssistantCitations(
            SID,
            TURN,
            TS,
            List.of(
                Citation.newBuilder().withSourceId("s1").withTitle("Docs").build(),
                Citation.of("s2", "body"))));
    print(new QueryEvent.ToolUse(SID, TURN, TS, CALL));
    print(new QueryEvent.ToolResult(SID, TURN, TS, CALL, ToolResult.success("x"), Map.of()));
    print(new QueryEvent.ToolResult(SID, TURN, TS, CALL, ToolResult.failure("nope"), Map.of()));
    print(new QueryEvent.ToolBlocked(SID, TURN, TS, CALL, "perm", "denied"));
    print(new QueryEvent.TurnEnded(SID, TURN, TS, FinishReason.STOP));
    print(
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
            + "[result] Read Failed: nope"
            + nl
            + "[blocked] Read: denied"
            + nl
            + "\n[turn-ended] STOP"
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

    streaming.print(
        List.of(
            new QueryEvent.AssistantText(SID, TURN, TS, "Hel"),
            new QueryEvent.AssistantText(SID, TURN, TS, "lo")));

    assertEquals(
        List.of("Hel", "Hello", "Hello"), flushed, "each delta, then the end of the stream");
  }

  @Test
  void longToolArgumentsAreCutWithTheirLengthNoted() {
    var arguments = "x".repeat(241);
    print(
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
    print(new QueryEvent.AssistantThinking(SID, TURN, TS, "hmm"));
    print(new QueryEvent.ContextWarning(SID, TURN, TS, 0.9));

    assertEquals("", printed());
  }

  @Test
  void rejectsNullEventsAndWritesNothing() {
    var ex = assertThrows(NullPointerException.class, () -> printer.print(null));

    assertEquals("events must not be null", ex.getMessage());
    assertEquals("", printed());
  }

  @Test
  void rejectsANullStream() {
    var ex = assertThrows(NullPointerException.class, () -> new ConsoleEventPrinter(null));
    assertEquals("out must not be null", ex.getMessage());
  }
}
