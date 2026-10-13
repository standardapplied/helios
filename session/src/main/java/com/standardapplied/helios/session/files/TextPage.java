/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.files;

import com.standardapplied.helios.core.common.Redactor;
import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.tool.ToolResult;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * {@link ReadTool}'s text path: one page of a file as line-numbered text. At most {@code limit}
 * lines starting at line {@code offset} are emitted, each capped at {@link ReadTool#MAX_LINE_BYTES}
 * and all of them together at {@link ReadTool#MAX_OUTPUT_BYTES}. Either cap appends a truncation
 * marker that teaches the model the next move, and the rest of the file is never read once a cap
 * fires. A line cut at its cap loses the start of a registered secret the cut went through.
 */
final class TextPage {

  private final Redactor redactor;
  private final StringBuilder out = new StringBuilder();
  private int linesEmitted;
  private long currentLine;
  private boolean truncatedByLines;
  private boolean truncatedByBytes;
  private boolean truncatedAnyLine;

  private TextPage(Redactor redactor) {
    this.redactor = redactor;
  }

  /**
   * Read one page of a text file.
   *
   * @param workspace the confining workspace
   * @param file a resolved regular file
   * @param offset 1-based first line
   * @param limit maximum lines emitted
   * @param secrets the session's registry, whose secrets a cut line must not end in part of
   * @return the page, or a failure naming the I/O error
   */
  static ToolResult read(
      WorkspaceRoot workspace, Path file, int offset, int limit, SecretRegistry secrets) {
    var page = new TextPage(secrets.redactor());
    try (var reader =
        new BufferedReader(
            new InputStreamReader(
                workspace.newInputStream(file, ReadTool.MAX_FILE_SIZE_BYTES),
                StandardCharsets.UTF_8))) {
      page.fill(reader, offset, limit);
    } catch (IOException e) {
      return ToolResult.failure("Read: I/O error reading file: " + e.getMessage());
    }
    return ToolResult.success(page.withMarker());
  }

  private void fill(BufferedReader reader, int offset, int limit) throws IOException {
    String line;
    while ((line = reader.readLine()) != null) {
      currentLine++;
      if (currentLine < offset) {
        continue;
      }
      if (linesEmitted >= limit) {
        truncatedByLines = true;
        return;
      }
      var entry = String.format("%6d\t%s%n", currentLine, capped(line));
      if (out.length() + entry.length() > ReadTool.MAX_OUTPUT_BYTES) {
        truncatedByBytes = true;
        return;
      }
      out.append(entry);
      linesEmitted++;
    }
  }

  private String capped(String line) {
    if (line.length() <= ReadTool.MAX_LINE_BYTES) {
      return line;
    }
    truncatedAnyLine = true;
    return redactor.trimPartialSecret(line.substring(0, ReadTool.MAX_LINE_BYTES))
        + " [line truncated to "
        + ReadTool.MAX_LINE_BYTES
        + " bytes]";
  }

  private String withMarker() {
    if (truncatedByLines) {
      out.append("[truncated at line ")
          .append(currentLine - 1)
          .append("; ")
          .append(linesEmitted)
          .append(" lines emitted. Use offset=")
          .append(currentLine)
          .append(" to continue, or Grep for a narrower target.]\n");
    } else if (truncatedByBytes) {
      out.append("[truncated: total output exceeded ")
          .append(ReadTool.MAX_OUTPUT_BYTES)
          .append(" bytes after ")
          .append(linesEmitted)
          .append(" lines. Use Grep to locate the section you need.]\n");
    } else if (truncatedAnyLine) {
      out.append("[note: at least one line exceeded ")
          .append(ReadTool.MAX_LINE_BYTES)
          .append(" bytes and was truncated mid-line.]\n");
    }
    return out.toString();
  }
}
