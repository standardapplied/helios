/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.anthropic.api.AnthropicJson;
import com.standardapplied.helios.anthropic.api.ApiStreamEvent;
import com.standardapplied.helios.anthropic.api.ApiUsage;
import com.standardapplied.helios.anthropic.api.ContentDelta;
import com.standardapplied.helios.anthropic.api.MessagesResponse;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.model.TransientStreamException;
import com.standardapplied.helios.core.provider.SseReader;

/**
 * Parses one Messages API stream, one event at a time, into {@link StreamEvent}s: text, thinking
 * and tool calls as their blocks stream and close, and the assembled response at {@code
 * message_stop} or the end of the body. A retryable API error ({@code overloaded_error} and its
 * peers) carries a {@link TransientStreamException}; the stream reads on after any error event.
 */
final class AnthropicStreamParser implements SseReader.Parser {

  private final ContentBlocks blocks = new ContentBlocks();
  private int inputTokens;
  private int outputTokens;
  private int cacheCreationInputTokens;
  private int cacheReadInputTokens;
  private String stopReason;
  private ContentDelta.StopDetails stopDetails;
  private boolean finished;

  @Override
  public StreamEvent parse(String payload) {
    try {
      return on(AnthropicJson.LENIENT.readValue(payload, ApiStreamEvent.class), payload);
    } catch (RuntimeException e) {
      return new StreamEvent.Error("Failed to parse stream event", e);
    }
  }

  @Override
  public boolean finished() {
    return finished;
  }

  @Override
  public StreamEvent complete() {
    return AnthropicResponseAssembler.done(blocks, usage(), stopReason, stopDetails);
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
      case "message_stop" -> {
        finished = true;
        yield complete();
      }
      case "error" -> error(event.error(), payload);
      case null, default -> null;
    };
  }

  /**
   * Input and cache token counts, reported on {@code message_start}; {@code message_delta} later
   * carries the running output count, and cache counts do not change after the start.
   */
  private StreamEvent messageStart(MessagesResponse message) {
    if (message == null || message.usage() == null) {
      return null;
    }
    var usage = message.usage();
    if (usage.inputTokens() != null) {
      inputTokens = usage.inputTokens();
    }
    if (usage.cacheCreationInputTokens() != null) {
      cacheCreationInputTokens = usage.cacheCreationInputTokens();
    }
    if (usage.cacheReadInputTokens() != null) {
      cacheReadInputTokens = usage.cacheReadInputTokens();
    }
    return null;
  }

  private StreamEvent messageDelta(ContentDelta delta, ApiUsage usage) {
    if (delta != null && delta.stopReason() != null) {
      stopReason = delta.stopReason();
      stopDetails = delta.stopDetails();
    }
    if (usage != null && usage.outputTokens() != null) {
      outputTokens = usage.outputTokens();
    }
    return null;
  }

  private static StreamEvent error(ApiStreamEvent.ApiError error, String payload) {
    var message = "API stream error: " + payload;
    var cause =
        error != null && error.isTransient()
            ? new TransientStreamException(message, null, AnthropicProvider.PROVIDER_NAME)
            : null;
    return new StreamEvent.Error(message, cause);
  }

  private Response.Usage usage() {
    if (inputTokens > 0
        || outputTokens > 0
        || cacheCreationInputTokens > 0
        || cacheReadInputTokens > 0) {
      return Response.Usage.of(
          inputTokens, outputTokens, cacheCreationInputTokens, cacheReadInputTokens);
    }
    return null;
  }
}
