/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.examples.session;

import com.standardapplied.helios.session.QueryEvent;
import java.io.PrintStream;
import java.util.Objects;

/**
 * Prints a session's {@link QueryEvent} stream for a command-line host. Assistant text is written
 * as it streams and flushed at once; grounding citations, tool use, tool results, blocked tools,
 * turn ends and the loop end are written one line each; every other event is skipped. Tool
 * arguments longer than 240 characters are cut, with the full length noted.
 *
 * <p>The printer writes only to the {@link PrintStream} it is given, which the host chooses, and
 * reads the stream on the calling thread until it ends:
 *
 * <pre>{@code
 * try (var events = session.events()) {
 *   Thread.ofVirtual().start(() -> new ConsoleEventPrinter(System.out).print(events));
 *   ...
 * }
 * }</pre>
 */
public final class ConsoleEventPrinter {

  private static final int MAX_ARGUMENT_CHARS = 240;

  private final PrintStream out;

  /**
   * Build a printer.
   *
   * @param out the stream every event is written to; non-null
   * @throws NullPointerException if {@code out} is null
   */
  public ConsoleEventPrinter(PrintStream out) {
    this.out = Objects.requireNonNull(out, "out must not be null");
  }

  /**
   * Print every event of {@code events} until it ends, then flush.
   *
   * @param events the session's events; non-null
   * @throws NullPointerException if {@code events} is null
   */
  public void print(Iterable<QueryEvent> events) {
    Objects.requireNonNull(events, "events must not be null");
    for (var event : events) {
      print(event);
    }
    out.flush();
  }

  private void print(QueryEvent event) {
    switch (event) {
      case QueryEvent.AssistantText text -> {
        out.print(text.text());
        out.flush();
      }
      case QueryEvent.AssistantCitations citations ->
          out.println(
              "\n[citations] "
                  + citations.citations().stream()
                      .map(c -> c.title() != null ? c.title() : c.sourceId())
                      .toList());
      case QueryEvent.ToolUse use ->
          out.println("\n[tool] " + use.call().name() + " " + truncate(use.call().arguments()));
      case QueryEvent.ToolResult result ->
          out.println(
              "[result] "
                  + result.call().name()
                  + " "
                  + (result.result().succeeded()
                      ? "ok"
                      : result.result().outcome().getClass().getSimpleName()
                          + ": "
                          + result.result().output()));
      case QueryEvent.ToolBlocked blocked ->
          out.println("[blocked] " + blocked.call().name() + ": " + blocked.reason());
      case QueryEvent.TurnEnded ended -> out.println("\n[turn-ended] " + ended.reason());
      case QueryEvent.RunEnded ended ->
          out.println("[run-ended] " + ended.result().getClass().getSimpleName());
      default -> {}
    }
  }

  private static String truncate(Object value) {
    var s = String.valueOf(value);
    if (s.length() > MAX_ARGUMENT_CHARS) {
      return s.substring(0, MAX_ARGUMENT_CHARS) + "... [" + s.length() + " chars]";
    }
    return s;
  }
}
