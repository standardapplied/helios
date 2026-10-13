/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.anthropic.api.AnthropicJson;
import com.standardapplied.helios.anthropic.api.ApiStreamEvent;
import com.standardapplied.helios.anthropic.api.ApiUsage;
import com.standardapplied.helios.anthropic.api.ContentDelta;
import com.standardapplied.helios.anthropic.api.MessagesResponse;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.ProviderException;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.model.TransientStreamException;
import com.standardapplied.helios.core.provider.SseReader;
import com.standardapplied.helios.core.provider.StreamErrorMessage;
import java.util.Objects;

/**
 * Parses one Messages API stream, one event at a time, into {@link StreamEvent}s: text, thinking
 * and tool calls as their blocks stream and close, and the assembled response at {@code
 * message_stop}. The stream succeeds only there, with a stop reason the assembler knows and no tool
 * use still open; an {@code error} event, an unreadable event, tool input that is not a JSON object
 * and any other {@code message_stop} end it in an error. A retryable API error ({@code
 * overloaded_error} and its peers) carries a {@link TransientStreamException}.
 */
final class AnthropicStreamParser implements SseReader.Parser {

  private final ContentBlocks blocks = new ContentBlocks();
  private Response.Usage counts = Response.Usage.of(0, 0, 0, 0);
  private String stopReason;
  private ContentDelta.StopDetails stopDetails;

  @Override
  public StreamEvent parse(String payload) {
    try {
      return on(AnthropicJson.LENIENT.readValue(payload, ApiStreamEvent.class), payload);
    } catch (ProviderException e) {
      return failed(e.getMessage(), e);
    } catch (RuntimeException e) {
      return failed("Failed to parse stream event", e);
    }
  }

  @Override
  public Response<Void> partial() {
    return AnthropicResponseAssembler.response(
        blocks, usage(), stopReason, stopDetails, FinishReason.ERROR);
  }

  private StreamEvent on(ApiStreamEvent event, String payload) {
    return switch (event.type()) {
      case "message_start" -> messageStart(event.message());
      case "content_block_start" -> {
        blocks.start(event.index(), event.contentBlock(), payload);
        yield null;
      }
      case "content_block_delta" ->
          event.delta() == null ? null : blocks.delta(event.index(), event.delta());
      case "content_block_stop" -> blocks.stop(event.index());
      case "message_delta" -> messageDelta(event.delta(), event.usage());
      case "message_stop" -> messageStop();
      case "error" -> error(event.error());
      case null, default -> null;
    };
  }

  private StreamEvent messageStart(MessagesResponse message) {
    if (message != null) {
      absorb(message.usage());
    }
    return null;
  }

  private StreamEvent messageDelta(ContentDelta delta, ApiUsage usage) {
    if (delta != null && delta.stopReason() != null) {
      stopReason = delta.stopReason();
      stopDetails = delta.stopDetails();
    }
    absorb(usage);
    return null;
  }

  /**
   * The token counts {@code usage} reports. {@code message_start} reports the input, cache and
   * initial output counts; each {@code message_delta} reports cumulative counts, which grow past
   * the start's input count when server tools run, so every count it carries replaces the last.
   * Counts whose sum overflows fail here, before they replace any count, so the partial stays
   * readable.
   */
  private void absorb(ApiUsage usage) {
    if (usage == null) {
      return;
    }
    counts =
        Response.Usage.of(
            Objects.requireNonNullElse(usage.inputTokens(), counts.inputTokens()),
            Objects.requireNonNullElse(usage.outputTokens(), counts.outputTokens()),
            Objects.requireNonNullElse(
                usage.cacheCreationInputTokens(), counts.cacheCreationInputTokens()),
            Objects.requireNonNullElse(
                usage.cacheReadInputTokens(), counts.cacheReadInputTokens()));
  }

  private StreamEvent messageStop() {
    var openToolUse = blocks.toolUses().open();
    if (openToolUse == null) {
      openToolUse = blocks.verbatim().open();
    }
    if (openToolUse != null) {
      return failed("message_stop while tool use '" + openToolUse + "' is still open", null);
    }
    if (AnthropicResponseAssembler.finishReason(stopReason) == null) {
      return failed("message_stop with unknown stop_reason: " + stopReason, null);
    }
    return AnthropicResponseAssembler.done(blocks, usage(), stopReason, stopDetails);
  }

  private StreamEvent error(ApiStreamEvent.ApiError error) {
    var message =
        error == null
            ? StreamErrorMessage.of("API stream error", null, null)
            : StreamErrorMessage.of("API stream error", error.type(), error.message());
    var cause =
        error != null && error.isTransient()
            ? new TransientStreamException(message, null, AnthropicProvider.PROVIDER_NAME)
            : null;
    return failed(message, cause);
  }

  private StreamEvent failed(String message, Exception cause) {
    return new StreamEvent.Error(message, cause, partial());
  }

  private Response.Usage usage() {
    if (counts.inputTokens() > 0
        || counts.outputTokens() > 0
        || counts.cacheCreationInputTokens() > 0
        || counts.cacheReadInputTokens() > 0) {
      return counts;
    }
    return null;
  }
}
