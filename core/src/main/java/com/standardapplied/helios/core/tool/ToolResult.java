/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.tool;

import com.standardapplied.helios.core.model.InlineFile;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Result of a tool execution.
 *
 * <p>A tool returns text ({@link #output}) and optional multimodal attachments ({@link
 * #attachments}) when it surfaced a file the model should consume natively rather than as text. Use
 * the attachments channel for image / PDF / audio outputs — for example a Read tool that returned a
 * PNG should pack the bytes into an {@link InlineFile} so the provider's vision channel sees the
 * image rather than the Base64 string.
 *
 * <p>The agent loop splices any returned {@link #attachments} into a synthetic follow-up user
 * message after the tool-result text reaches the model. That keeps wire encoding uniform across
 * providers — every provider already handles {@code Message.user(text, inlineFiles)}, no
 * per-provider {@code tool_result} multimodal wiring needed.
 *
 * <p>{@link #outcome} says what happened to the call; {@link #output} is what the model reads about
 * it. Each factory sets both, so a framework outcome and its text cannot drift apart.
 *
 * @param outcome what happened to the call; non-null
 * @param output the output (result text or error message); non-null, may be empty
 * @param attachments multimodal files to surface to the model alongside {@link #output}; non-null
 *     (use empty list when none), defensively copied
 */
public record ToolResult(ToolOutcome outcome, String output, List<InlineFile> attachments) {

  /**
   * Canonical constructor. Normalises {@code attachments} to an immutable copy and rejects null —
   * the empty list is the no-attachment sentinel, never null. {@code outcome} and {@code output}
   * are rejected if null; an empty string is the right encoding of "no text".
   */
  public ToolResult {
    Objects.requireNonNull(outcome, "outcome must not be null");
    Objects.requireNonNull(output, "output must not be null (use empty string for no text)");
    attachments = attachments == null ? List.of() : List.copyOf(attachments);
  }

  /** Successful result with text only. */
  public static ToolResult success(String output) {
    return new ToolResult(new ToolOutcome.Succeeded(), output, List.of());
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
    return new ToolResult(new ToolOutcome.Succeeded(), output, attachments);
  }

  /** Failed result with text error: the tool ran and reported failure. */
  public static ToolResult failure(String error) {
    return new ToolResult(new ToolOutcome.Failed(), error, List.of());
  }

  /**
   * The tool, or the dispatch of it, threw {@code cause}.
   *
   * @param toolName the called tool's name; non-null
   * @param cause what was thrown; non-null
   * @return a {@link ToolOutcome.Threw} result naming the tool and the cause's message, or its
   *     simple class name when the message is null
   */
  public static ToolResult threw(String toolName, Throwable cause) {
    Objects.requireNonNull(toolName, "toolName must not be null");
    Objects.requireNonNull(cause, "cause must not be null");
    var message =
        cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    return new ToolResult(
        new ToolOutcome.Threw(cause.getClass().getName()),
        "tool '" + toolName + "' threw: " + message,
        List.of());
  }

  /**
   * The call exceeded {@code limit}.
   *
   * @param toolName the called tool's name; non-null
   * @param limit the limit in force for the call; positive
   * @return a {@link ToolOutcome.TimedOut} result
   */
  public static ToolResult timedOut(String toolName, Duration limit) {
    Objects.requireNonNull(toolName, "toolName must not be null");
    return new ToolResult(
        new ToolOutcome.TimedOut(limit),
        "tool '" + toolName + "' timed out after " + limit,
        List.of());
  }

  /**
   * No tool named {@code toolName} is registered.
   *
   * @param toolName the name the model called; non-null
   * @return a {@link ToolOutcome.NotFound} result
   */
  public static ToolResult notFound(String toolName) {
    Objects.requireNonNull(toolName, "toolName must not be null");
    return new ToolResult(new ToolOutcome.NotFound(), "tool not found: " + toolName, List.of());
  }

  /**
   * A hook refused or replaced the call before it ran.
   *
   * @param by the rejecting hook's name; non-blank
   * @param reason the hook's reason; non-blank
   * @return a {@link ToolOutcome.Rejected} result
   */
  public static ToolResult rejected(String by, String reason) {
    return new ToolResult(
        new ToolOutcome.Rejected(by, reason), "blocked by " + by + ": " + reason, List.of());
  }

  /**
   * This result with {@code replacement} as its output; the outcome and attachments are kept.
   *
   * @param replacement the new output; non-null
   * @return a result that differs from this one only in its output
   */
  public ToolResult withOutput(String replacement) {
    return new ToolResult(outcome, replacement, attachments);
  }

  /**
   * Whether the tool ran and reported success.
   *
   * @return {@code true} only for {@link ToolOutcome.Succeeded}
   */
  public boolean succeeded() {
    return outcome instanceof ToolOutcome.Succeeded;
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
