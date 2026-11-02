/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.protocol;

import static com.standardapplied.helios.repl.protocol.ProcessTransport.MAX_CAPTURED_OUTPUT_CHARS;

/**
 * Output a sandbox produced since the last drain, kept up to {@link
 * ProcessTransport#MAX_CAPTURED_OUTPUT_CHARS}. Text past the cap is counted and dropped, so a
 * sandbox that never stops printing costs the host a bounded buffer. Thread-safe: one thread reads
 * the sandbox while another drains.
 */
public final class CapturedOutput {

  /** Text a buffer kept and the count of characters it dropped, not yet joined by the marker. */
  public record Cut(String kept, long discarded) {

    /** The kept text, followed by {@code \n[output truncated: <n> more chars discarded]} if due. */
    public String text() {
      return discarded == 0
          ? kept
          : kept + "\n[output truncated: " + discarded + " more chars discarded]";
    }
  }

  private final StringBuilder kept = new StringBuilder();
  private long discarded;

  /**
   * Keeps as much of {@code text} as the cap allows and counts the rest. Nothing is kept once
   * anything was dropped, so the kept text stays an unbroken prefix of the output.
   */
  public synchronized void append(String text) {
    var room =
        discarded > 0 ? 0 : Math.min(text.length(), MAX_CAPTURED_OUTPUT_CHARS - kept.length());
    kept.append(text, 0, room);
    discarded += text.length() - room;
  }

  /** Appends what another buffer kept and counts what it dropped. */
  public synchronized void append(Cut cut) {
    append(cut.kept());
    discard(cut.discarded());
  }

  /** Appends {@code line}, separated by {@code \n} from anything already kept. */
  public synchronized void appendLine(String line) {
    if (!kept.isEmpty()) {
      append("\n");
    }
    append(line);
  }

  /** Counts {@code chars} characters a caller dropped before they reached this buffer. */
  public synchronized void discard(long chars) {
    discarded += chars;
  }

  /**
   * Returns what was kept and dropped since the last read and starts over. A surrogate pair the cap
   * would split is dropped whole.
   */
  public synchronized Cut take() {
    if (kept.length() == MAX_CAPTURED_OUTPUT_CHARS
        && Character.isHighSurrogate(kept.charAt(MAX_CAPTURED_OUTPUT_CHARS - 1))) {
      kept.setLength(MAX_CAPTURED_OUTPUT_CHARS - 1);
      discarded++;
    }
    var cut = new Cut(kept.toString(), discarded);
    kept.setLength(0);
    discarded = 0;
    return cut;
  }

  /**
   * Returns {@link #take()} as {@link Cut#text() text}.
   *
   * @return the captured text, or an empty string if there is none
   */
  public synchronized String drain() {
    return take().text();
  }
}
