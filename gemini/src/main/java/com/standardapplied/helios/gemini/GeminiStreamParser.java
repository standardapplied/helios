/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.provider.SseReader;
import com.standardapplied.helios.gemini.api.GeminiJson;
import com.standardapplied.helios.gemini.api.InteractionResponse;
import com.standardapplied.helios.gemini.api.InteractionUsage;
import com.standardapplied.helios.gemini.api.StreamingEvent;
import java.util.Map;

/**
 * Parses one Interactions API stream, one event at a time, into {@link StreamEvent}s: the step
 * events through {@link StreamedSteps}, the interaction's id, status and usage from its envelope
 * and status updates, and the assembled response at the end of the body. An {@code error} event
 * ends the stream with a {@link GeminiException} carrying the reported code.
 */
final class GeminiStreamParser implements SseReader.Parser {

  private final boolean propagateInteractionId;
  private final String apiVersion;
  private final StreamedSteps steps = new StreamedSteps();
  private InteractionUsage usage;
  private String interactionId;
  private String status;
  private boolean finished;

  /**
   * A parser for a stream of the interactions API {@code apiVersion}, whose response records its
   * interaction id when {@code propagateInteractionId} is set, for server-side continuation.
   */
  GeminiStreamParser(boolean propagateInteractionId, String apiVersion) {
    this.propagateInteractionId = propagateInteractionId;
    this.apiVersion = apiVersion;
  }

  @Override
  public StreamEvent parse(String payload) {
    try {
      return on(GeminiJson.LENIENT.readValue(payload, StreamingEvent.class));
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
    return GeminiResponseAssembler.done(
        steps.result(), usage, status, propagateInteractionId ? interactionId : null, apiVersion);
  }

  private StreamEvent on(StreamingEvent event) {
    return switch (event.eventType()) {
      case "error" -> error(event.error());
      case "interaction.created", "interaction.completed" -> envelope(event.interaction());
      case "interaction.status_update", "interaction.in_progress", "interaction.requires_action" ->
          statusUpdate(event.interactionId(), event.status());
      case "step.start" -> steps.start(event.index(), event.step());
      case "step.delta" -> steps.delta(event.index(), event.argumentsDelta(), event.delta());
      case "step.stop" -> steps.stop(event.index());
      case null, default -> null;
    };
  }

  private StreamEvent error(Map<String, Object> error) {
    var message = "API error";
    var statusCode = 0;
    if (error != null) {
      if (error.get("message") != null) {
        message = "API error: " + error.get("message");
      }
      if (error.get("code") instanceof Number code && code.intValue() > 0) {
        statusCode = code.intValue();
      }
    }
    finished = true;
    return new StreamEvent.Error(message, new GeminiException(message, statusCode));
  }

  private StreamEvent envelope(InteractionResponse interaction) {
    if (interaction != null) {
      statusUpdate(interaction.id(), interaction.status());
      if (interaction.usage() != null) {
        usage = interaction.usage();
      }
    }
    return null;
  }

  private StreamEvent statusUpdate(String id, String newStatus) {
    if (id != null) {
      interactionId = id;
    }
    if (newStatus != null) {
      status = newStatus;
    }
    return null;
  }
}
