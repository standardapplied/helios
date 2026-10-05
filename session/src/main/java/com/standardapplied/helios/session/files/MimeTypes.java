/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.files;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * How {@link ReadTool} classifies a file: a lookup of the file's extension in a table of known MIME
 * types, each tagged with the channel Read returns it through, and a NUL-byte sniff of a confined
 * stream for everything the table does not name. No platform detector runs, so nothing reopens an
 * unconfined path.
 */
final class MimeTypes {

  /** The channel Read returns a file through. */
  enum Channel {
    /** Sent as an inline attachment the provider consumes natively. */
    ATTACHMENT,
    /** Decoded as UTF-8 and returned as line-numbered text. */
    TEXT
  }

  /**
   * A recognised file type.
   *
   * @param name the MIME type
   * @param channel how Read returns the file
   */
  record MimeType(String name, Channel channel) {}

  /** Number of bytes sniffed when deciding whether an unrecognised file is text. */
  static final int SNIFF_BYTES = 8 * 1024;

  private static final Map<String, MimeType> BY_EXTENSION =
      table(
          attachment("application/pdf", "pdf"),
          attachment("image/png", "png"),
          attachment("image/jpeg", "jpg", "jpeg"),
          attachment("image/gif", "gif"),
          attachment("image/webp", "webp"),
          text("application/json", "json"),
          text("application/xml", "xml"),
          text("application/yaml", "yaml", "yml"),
          text("text/html", "html", "htm"),
          text("text/css", "css"),
          text("text/javascript", "js", "mjs"),
          text("text/markdown", "md", "markdown"),
          text(
              "text/plain",
              "java",
              "kt",
              "scala",
              "py",
              "rb",
              "go",
              "rs",
              "c",
              "cpp",
              "h",
              "hpp",
              "ts",
              "tsx",
              "csv",
              "tsv",
              "log",
              "txt"));

  private MimeTypes() {}

  /**
   * Look a file's extension up in the table, case-insensitively.
   *
   * @param file the file; its name is read, never its content
   * @return the recognised type, or empty for a missing or unknown extension
   */
  static Optional<MimeType> detect(Path file) {
    var name = file.getFileName().toString().toLowerCase(Locale.ROOT);
    var dot = name.lastIndexOf('.');
    var extension = dot >= 0 && dot < name.length() - 1 ? name.substring(dot + 1) : "";
    return Optional.ofNullable(BY_EXTENSION.get(extension));
  }

  /**
   * Whether the first {@link #SNIFF_BYTES} bytes are free of NUL, present in essentially every
   * binary format and absent in real-world text. Lets an unrecognised but clearly textual file (a
   * config file without a standard extension) be served as text.
   *
   * @param workspace the confining workspace
   * @param file a resolved regular file
   * @param maxBytes the size limit the confined open enforces
   * @return {@code true} when no NUL was found; {@code false} on NUL or any I/O failure
   */
  static boolean isLikelyText(WorkspaceRoot workspace, Path file, long maxBytes) {
    try (var in = workspace.newInputStream(file, maxBytes)) {
      var buf = new byte[SNIFF_BYTES];
      var n = in.readNBytes(buf, 0, buf.length);
      for (var i = 0; i < n; i++) {
        if (buf[i] == 0) {
          return false;
        }
      }
      return true;
    } catch (IOException unreadable) {
      return false;
    }
  }

  private static Row attachment(String name, String... extensions) {
    return new Row(new MimeType(name, Channel.ATTACHMENT), List.of(extensions));
  }

  private static Row text(String name, String... extensions) {
    return new Row(new MimeType(name, Channel.TEXT), List.of(extensions));
  }

  private static Map<String, MimeType> table(Row... rows) {
    return Stream.of(rows)
        .flatMap(row -> row.extensions().stream().map(ext -> Map.entry(ext, row.type())))
        .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  private record Row(MimeType type, List<String> extensions) {}
}
