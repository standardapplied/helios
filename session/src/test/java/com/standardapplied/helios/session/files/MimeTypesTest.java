/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.tool.ToolContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

final class MimeTypesTest {

  @ParameterizedTest
  @CsvSource({
    "doc.pdf, application/pdf",
    "pic.png, image/png",
    "pic.jpg, image/jpeg",
    "pic.jpeg, image/jpeg",
    "pic.gif, image/gif",
    "pic.webp, image/webp",
    "data.json, application/json",
    "data.xml, application/xml",
    "conf.yaml, application/yaml",
    "conf.yml, application/yaml",
    "page.html, text/html",
    "page.htm, text/html",
    "site.css, text/css",
    "app.js, text/javascript",
    "app.mjs, text/javascript",
    "Main.java, text/plain",
    "Main.kt, text/plain",
    "Main.scala, text/plain",
    "main.py, text/plain",
    "main.rb, text/plain",
    "main.go, text/plain",
    "main.rs, text/plain",
    "main.c, text/plain",
    "main.cpp, text/plain",
    "main.h, text/plain",
    "main.hpp, text/plain",
    "main.ts, text/plain",
    "main.tsx, text/plain",
    "README.md, text/markdown",
    "README.markdown, text/markdown",
    "rows.csv, text/plain",
    "rows.tsv, text/plain",
    "app.log, text/plain",
    "notes.txt, text/plain",
    "SHOUT.PNG, image/png",
    "archive.tar.json, application/json"
  })
  void recognisedExtensionMapsToItsMimeType(String name, String mimeType) {
    assertEquals(mimeType, MimeTypes.detect(Path.of(name)).orElseThrow().name());
  }

  @ParameterizedTest
  @CsvSource({
    "doc.pdf, ATTACHMENT",
    "pic.png, ATTACHMENT",
    "pic.jpeg, ATTACHMENT",
    "pic.gif, ATTACHMENT",
    "pic.webp, ATTACHMENT",
    "data.json, TEXT",
    "data.xml, TEXT",
    "conf.yml, TEXT",
    "page.html, TEXT",
    "site.css, TEXT",
    "app.js, TEXT",
    "README.md, TEXT",
    "notes.txt, TEXT"
  })
  void onlyImagesAndPdfTravelAsAttachments(String name, MimeTypes.Channel channel) {
    assertEquals(channel, MimeTypes.detect(Path.of(name)).orElseThrow().channel());
  }

  @ParameterizedTest
  @ValueSource(strings = {"Dockerfile", "archive.bin", "trailing.", ".hidden", "x.unknown"})
  void unknownExtensionHasNoMimeType(String name) {
    assertTrue(MimeTypes.detect(Path.of(name)).isEmpty());
  }

  @Test
  void aFileTheConfinedOpenRefusesIsNotLikelyText(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("notes"), "plain text");
    var workspace = WorkspaceRoot.of(tmp);
    var notes = workspace.resolveSafe("notes");

    assertTrue(MimeTypes.isLikelyText(workspace, notes, 1024));
    assertFalse(MimeTypes.isLikelyText(workspace, notes, 1));
  }

  @Test
  void unknownExtensionWithBinaryContentIsRefusedNamingUnknownMime(@TempDir Path tmp)
      throws IOException {
    Files.write(tmp.resolve("blob.bin"), new byte[] {1, 0, 2});

    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create())
            .tool()
            .execute(Map.of("path", "blob.bin"), ToolContext.noop());

    assertFalse(result.success());
    assertEquals(
        "Read: refusing to decode binary file as text (detected MIME unknown). Images and PDFs are"
            + " returned as attachments; for other binary formats use a dedicated tool or extract"
            + " the payload server-side before passing the bytes through.",
        result.output());
  }

  @Test
  void recognisedTextExtensionIsServedAsTextEvenWithBinaryContent(@TempDir Path tmp)
      throws IOException {
    Files.write(tmp.resolve("odd.json"), new byte[] {'{', 0, '}'});

    var result =
        ReadTool.binding(WorkspaceRoot.of(tmp), InMemoryFileTracker.create())
            .tool()
            .execute(Map.of("path", "odd.json"), ToolContext.noop());

    assertTrue(result.success(), result.output());
    assertFalse(result.hasAttachments());
  }
}
