/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToolResultTest {

  @Test
  void successWithOutput() {
    var result = ToolResult.success("Done!");

    assertTrue(result.success());
    assertEquals("Done!", result.output());
    assertNull(result.data());
  }

  @Test
  void successWithData() {
    var data = java.util.Map.of("key", "value");
    var result = ToolResult.success("Result", data);

    assertTrue(result.success());
    assertEquals("Result", result.output());
    assertEquals(data, result.data());
  }

  @Test
  void failureWithMessage() {
    var result = ToolResult.failure("Something went wrong");

    assertFalse(result.success());
    assertEquals("Something went wrong", result.output());
    assertNull(result.data());
  }

  @Test
  void failureWithCause() {
    var cause = new RuntimeException("Root cause");
    var result = ToolResult.failure("Operation failed", cause);

    assertFalse(result.success());
    assertEquals("Operation failed: Root cause", result.output());
  }

  @Test
  void failureWithNullCause() {
    var result = ToolResult.failure("Operation failed", null);

    assertFalse(result.success());
    assertEquals("Operation failed", result.output());
  }

  @Test
  void failureWithCauseNoMessage() {
    var cause = new RuntimeException();
    var result = ToolResult.failure("Operation failed", cause);

    assertFalse(result.success());
    assertEquals("Operation failed", result.output());
  }

  // ── Layer 2: multimodal attachments ────────────────────────────────────

  @Test
  void successByDefaultHasNoAttachments() {
    var result = ToolResult.success("plain text");
    assertFalse(result.hasAttachments());
    assertEquals(0, result.attachments().size());
  }

  @Test
  void failureByDefaultHasNoAttachments() {
    var result = ToolResult.failure("oops");
    assertFalse(result.hasAttachments());
    assertEquals(0, result.attachments().size());
  }

  @Test
  void successWithAttachmentsCarriesInlineFiles() {
    var png =
        ai.singlr.core.model.InlineFile.of(new byte[] {(byte) 0x89, 'P', 'N', 'G'}, "image/png");
    var result =
        ToolResult.successWithAttachments(
            "Returned image/png for inspection.", java.util.List.of(png));
    assertTrue(result.success());
    assertTrue(result.hasAttachments());
    assertEquals(1, result.attachments().size());
    assertEquals("image/png", result.attachments().getFirst().mimeType());
    assertNull(result.data(), "successWithAttachments leaves data null");
  }

  @Test
  void successWithAttachmentsDefensivelyCopies() {
    var png =
        ai.singlr.core.model.InlineFile.of(new byte[] {(byte) 0x89, 'P', 'N', 'G'}, "image/png");
    var mutable = new java.util.ArrayList<ai.singlr.core.model.InlineFile>();
    mutable.add(png);
    var result = ToolResult.successWithAttachments("note", mutable);
    mutable.clear();
    assertEquals(1, result.attachments().size(), "subsequent mutation of input list must not leak");
    org.junit.jupiter.api.Assertions.assertThrows(
        UnsupportedOperationException.class,
        () -> result.attachments().add(png),
        "attachments must be unmodifiable");
  }

  @Test
  void canonicalConstructorRejectsNullOutput() {
    var npe =
        org.junit.jupiter.api.Assertions.assertThrows(
            NullPointerException.class,
            () -> new ToolResult(true, null, null, java.util.List.of()));
    assertTrue(npe.getMessage().contains("output"), npe.getMessage());
  }

  @Test
  void canonicalConstructorAcceptsNullAttachmentsAsEmpty() {
    var result = new ToolResult(true, "text", null, null);
    assertFalse(result.hasAttachments());
    assertEquals(0, result.attachments().size());
  }
}
