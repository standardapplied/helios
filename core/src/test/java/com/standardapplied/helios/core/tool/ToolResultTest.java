/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.InlineFile;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class ToolResultTest {

  private static final InlineFile PNG =
      InlineFile.of(new byte[] {(byte) 0x89, 'P', 'N', 'G'}, "image/png");

  @Test
  void successWithOutput() {
    var result = ToolResult.success("Done!");

    assertEquals(new ToolOutcome.Succeeded(), result.outcome());
    assertTrue(result.succeeded());
    assertEquals("Done!", result.output());
  }

  @Test
  void failureWithMessage() {
    var result = ToolResult.failure("Something went wrong");

    assertEquals(new ToolOutcome.Failed(), result.outcome());
    assertFalse(result.succeeded());
    assertEquals("Something went wrong", result.output());
  }

  @Test
  void threwNamesTheToolTheCauseTypeAndItsMessage() {
    var result = ToolResult.threw("search", new IllegalStateException("index offline"));

    assertEquals(new ToolOutcome.Threw("java.lang.IllegalStateException"), result.outcome());
    assertFalse(result.succeeded());
    assertEquals("tool 'search' threw: index offline", result.output());
  }

  @Test
  void threwWithoutAMessageNamesTheCauseSimpleClassName() {
    var result = ToolResult.threw("search", new StackOverflowError());

    assertEquals(new ToolOutcome.Threw("java.lang.StackOverflowError"), result.outcome());
    assertEquals("tool 'search' threw: StackOverflowError", result.output());
  }

  @Test
  void timedOutCarriesTheLimit() {
    var result = ToolResult.timedOut("search", Duration.ofMinutes(1));

    assertEquals(new ToolOutcome.TimedOut(Duration.ofMinutes(1)), result.outcome());
    assertFalse(result.succeeded());
    assertEquals("tool 'search' timed out after PT1M", result.output());
  }

  @Test
  void unknownCarriesTheLimitAndSaysTheEffectsMayStillLand() {
    var result = ToolResult.unknown("write", Duration.ofMinutes(1));

    assertEquals(new ToolOutcome.Unknown(Duration.ofMinutes(1)), result.outcome());
    assertFalse(result.succeeded());
    assertEquals(
        "tool 'write' timed out after PT1M and did not stop; it may still be running and its"
            + " effects may still land",
        result.output());
  }

  @Test
  void notFoundNamesTheTool() {
    var result = ToolResult.notFound("serch");

    assertEquals(new ToolOutcome.NotFound(), result.outcome());
    assertFalse(result.succeeded());
    assertEquals("tool not found: serch", result.output());
  }

  @Test
  void rejectedNamesTheHookAndReason() {
    var result = ToolResult.rejected("AuditHook", "not allowed");

    assertEquals(new ToolOutcome.Rejected("AuditHook", "not allowed"), result.outcome());
    assertFalse(result.succeeded());
    assertEquals("blocked by AuditHook: not allowed", result.output());
  }

  @Test
  void theFrameworkFactoriesRejectAMissingToolNameOrCause() {
    var cause = new IllegalStateException("x");
    assertRejectsNull("toolName must not be null", () -> ToolResult.threw(null, cause));
    assertRejectsNull("cause must not be null", () -> ToolResult.threw("search", null));
    assertRejectsNull(
        "toolName must not be null", () -> ToolResult.timedOut(null, Duration.ofSeconds(1)));
    assertRejectsNull(
        "toolName must not be null", () -> ToolResult.unknown(null, Duration.ofSeconds(1)));
    assertRejectsNull("toolName must not be null", () -> ToolResult.notFound(null));
  }

  @Test
  void withOutputReplacesOnlyTheOutput() {
    var original =
        new ToolResult(new ToolOutcome.Failed(), "original", List.of(PNG)).withOutput("rewritten");

    assertEquals(new ToolResult(new ToolOutcome.Failed(), "rewritten", List.of(PNG)), original);
  }

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
    var result =
        ToolResult.successWithAttachments("Returned image/png for inspection.", List.of(PNG));
    assertTrue(result.succeeded());
    assertTrue(result.hasAttachments());
    assertEquals(List.of(PNG), result.attachments());
  }

  @Test
  void successWithAttachmentsDefensivelyCopies() {
    var mutable = new ArrayList<InlineFile>();
    mutable.add(PNG);
    var result = ToolResult.successWithAttachments("note", mutable);
    mutable.clear();
    assertEquals(1, result.attachments().size(), "subsequent mutation of input list must not leak");
    assertThrows(
        UnsupportedOperationException.class,
        () -> result.attachments().add(PNG),
        "attachments must be unmodifiable");
  }

  @Test
  void canonicalConstructorRejectsNullOutcome() {
    var npe = assertThrows(NullPointerException.class, () -> new ToolResult(null, "", List.of()));
    assertEquals("outcome must not be null", npe.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullOutput() {
    var npe =
        assertThrows(
            NullPointerException.class,
            () -> new ToolResult(new ToolOutcome.Succeeded(), null, List.of()));
    assertTrue(npe.getMessage().contains("output"), npe.getMessage());
  }

  @Test
  void canonicalConstructorAcceptsNullAttachmentsAsEmpty() {
    var result = new ToolResult(new ToolOutcome.Succeeded(), "text", null);
    assertFalse(result.hasAttachments());
    assertEquals(0, result.attachments().size());
  }

  private static void assertRejectsNull(String message, Executable factory) {
    assertEquals(message, assertThrows(NullPointerException.class, factory).getMessage());
  }
}
