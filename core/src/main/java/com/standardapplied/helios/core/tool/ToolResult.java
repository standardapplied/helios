/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.tool;

import ai.singlr.core.model.InlineFile;
import java.util.List;
import java.util.Objects;

/**
 * Result of a tool execution.
 *
 * <p>A tool can return text ({@link #output}), optional structured data ({@link #data}, free-form
 * for the deployer), and optional multimodal attachments ({@link #attachments}) when the tool
 * surfaced a file the model should consume natively rather than as text. Use the attachments
 * channel for image / PDF / audio outputs — for example a Read tool that returned a PNG should pack
 * the bytes into an {@link InlineFile} so the provider's vision channel sees the image rather than
 * the Base64 string.
 *
 * <p>The agent loop splices any returned {@link #attachments} into a synthetic follow-up user
 * message after the tool-result text reaches the model. That keeps wire encoding uniform across
 * providers — every provider already handles {@code Message.user(text, inlineFiles)}, no
 * per-provider {@code tool_result} multimodal wiring needed.
 *
 * @param success whether the execution succeeded
 * @param output the output (result text or error message); non-null, may be empty
 * @param data optional structured data from the tool; free-form, may be null
 * @param attachments multimodal files to surface to the model alongside {@link #output}; non-null
 *     (use empty list when none), defensively copied
 */
public record ToolResult(
    boolean success, String output, Object data, List<InlineFile> attachments) {

  /**
   * Canonical constructor. Normalises {@code attachments} to an immutable copy and rejects null —
   * the empty list is the no-attachment sentinel, never null. {@code output} is also rejected if
   * null; an empty string is the right encoding of "no text".
   */
  public ToolResult {
    Objects.requireNonNull(output, "output must not be null (use empty string for no text)");
    attachments = attachments == null ? List.of() : List.copyOf(attachments);
  }

  /** Successful result with text only. */
  public static ToolResult success(String output) {
    return new ToolResult(true, output, null, List.of());
  }

  /** Successful result with text plus deployer-supplied structured data. */
  public static ToolResult success(String output, Object data) {
    return new ToolResult(true, output, data, List.of());
  }

  /**
   * Successful result with text plus multimodal attachments. The text typically names what was
   * attached (e.g. {@code "Returned image/png file: chart.png (245 KB)"}) so the model has a handle
   * in conversation history even before the attached bytes reach it.
   *
   * @param output result text; non-null, may be empty
   * @param attachments multimodal files surfaced to the model; non-null
   * @return a successful {@link ToolResult} carrying both
   */
  public static ToolResult successWithAttachments(String output, List<InlineFile> attachments) {
    return new ToolResult(true, output, null, attachments);
  }

  /** Failed result with text error. */
  public static ToolResult failure(String error) {
    return new ToolResult(false, error, null, List.of());
  }

  /** Failed result with cause; appends {@code cause.getMessage()} when present. */
  public static ToolResult failure(String error, Exception cause) {
    var message = error;
    if (cause != null && cause.getMessage() != null) {
      message = error + ": " + cause.getMessage();
    }
    return new ToolResult(false, message, null, List.of());
  }

  /**
   * Whether this result carries any multimodal attachments. Equivalent to {@code
   * !attachments().isEmpty()} but more readable at call sites that branch on the presence of
   * attached files.
   *
   * @return {@code true} when there is at least one {@link InlineFile}
   */
  public boolean hasAttachments() {
    return !attachments.isEmpty();
  }
}
