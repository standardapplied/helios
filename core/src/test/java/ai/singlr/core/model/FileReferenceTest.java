/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class FileReferenceTest {

  @Test
  void createsAbsoluteHttpsReference() {
    var file =
        FileReference.of("https://generativelanguage.googleapis.com/v1beta/files/abc", "video/mp4");

    assertEquals("https://generativelanguage.googleapis.com/v1beta/files/abc", file.uri());
    assertEquals("video/mp4", file.mimeType());
  }

  @Test
  void createsGoogleCloudStorageReference() {
    var file = FileReference.of("gs://bucket/video.mp4", "video/mp4");

    assertEquals("gs://bucket/video.mp4", file.uri());
  }

  @Test
  void rejectsInvalidUri() {
    assertThrows(IllegalArgumentException.class, () -> FileReference.of("", "video/mp4"));
    assertThrows(IllegalArgumentException.class, () -> FileReference.of("files/abc", "video/mp4"));
    assertThrows(
        IllegalArgumentException.class, () -> FileReference.of("https:video.mp4", "video/mp4"));
    assertThrows(
        IllegalArgumentException.class, () -> FileReference.of("gs:/video.mp4", "video/mp4"));
    assertThrows(
        IllegalArgumentException.class,
        () -> FileReference.of("file:///tmp/video.mp4", "video/mp4"));
    assertThrows(
        IllegalArgumentException.class,
        () -> FileReference.of("https://user:secret@example.com/video.mp4", "video/mp4"));
  }

  @Test
  void rejectsInvalidMimeType() {
    assertThrows(
        IllegalArgumentException.class,
        () -> FileReference.of("https://example.com/video.mp4", ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> FileReference.of("https://example.com/video.mp4", "video"));
    assertThrows(
        IllegalArgumentException.class,
        () -> FileReference.of("https://example.com/video.mp4", "video/mp4\nX-Test: bad"));
  }
}
