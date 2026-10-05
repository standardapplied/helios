/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session;

import java.io.PrintStream;
import java.util.Objects;
import java.util.concurrent.Flow;

/**
 * Prints a session's {@link QueryEvent} stream for a command-line host. Assistant text is written
 * as it streams and flushed at once; grounding citations, tool use, tool results, blocked tools,
 * turn ends and the loop end are written one line each; every other event is skipped. A stream
 * error is written as one line too. Tool arguments longer than 240 characters are cut, with the
 * full length noted.
 *
 * <p>The printer writes only to the {@link PrintStream} it is given, which the host chooses:
 *
 * <pre>{@code
 * session.events().subscribe(new ConsoleEventPrinter(System.out));
 * }</pre>
 */
public final class ConsoleEventPrinter implements Flow.Subscriber<QueryEvent> {

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

  @Override
  public void onSubscribe(Flow.Subscription subscription) {
    subscription.request(Long.MAX_VALUE);
  }

  @Override
  public void onNext(QueryEvent event) {
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
                  + (result.result().success() ? "ok" : "FAILED: " + result.result().output()));
      case QueryEvent.ToolBlocked blocked ->
          out.println("[blocked] " + blocked.call().name() + ": " + blocked.reason());
      case QueryEvent.TurnEnded ended -> out.println("\n[turn-ended] " + ended.reason());
      case QueryEvent.LoopEnded ended ->
          out.println("[loop-ended] " + ended.result().getClass().getSimpleName());
      default -> {}
    }
  }

  @Override
  public void onError(Throwable throwable) {
    out.println("[stream-error] " + throwable);
  }

  @Override
  public void onComplete() {
    out.flush();
  }

  private static String truncate(Object value) {
    var s = String.valueOf(value);
    if (s.length() > MAX_ARGUMENT_CHARS) {
      return s.substring(0, MAX_ARGUMENT_CHARS) + "... [" + s.length() + " chars]";
    }
    return s;
  }
}
