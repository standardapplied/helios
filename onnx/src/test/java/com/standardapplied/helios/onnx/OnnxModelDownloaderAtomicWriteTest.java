/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.onnx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Hermetic tests for {@link OnnxModelDownloader#writeAtomically(InputStream, Path)}. A model file
 * that is present and non-empty is taken for complete on the next run, so a download that breaks
 * off must leave nothing at the destination.
 */
class OnnxModelDownloaderAtomicWriteTest {

  @Test
  void completeDownloadLandsAtDestination(@TempDir Path tmp) throws IOException {
    var destination = tmp.resolve("model/model.onnx");
    var bytes = "weights".getBytes(StandardCharsets.UTF_8);

    OnnxModelDownloader.writeAtomically(new ByteArrayInputStream(bytes), destination);

    assertArrayEquals(bytes, Files.readAllBytes(destination));
    assertEquals(List.of(destination), entriesOf(destination.getParent()));
  }

  @Test
  void brokenDownloadLeavesNoFileBehind(@TempDir Path tmp) throws IOException {
    var destination = tmp.resolve("model/model.onnx");
    var broken =
        new InputStream() {
          private int served;

          @Override
          public int read() throws IOException {
            if (served++ < 3) {
              return 'x';
            }
            throw new IOException("connection reset");
          }
        };

    assertThrows(IOException.class, () -> OnnxModelDownloader.writeAtomically(broken, destination));

    assertFalse(Files.exists(destination));
    assertEquals(List.of(), entriesOf(destination.getParent()));
  }

  private static List<Path> entriesOf(Path directory) throws IOException {
    try (Stream<Path> entries = Files.list(directory)) {
      return entries.toList();
    }
  }
}
