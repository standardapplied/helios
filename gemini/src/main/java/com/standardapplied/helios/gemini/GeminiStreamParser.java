/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.ProviderException;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.provider.SseReader;
import com.standardapplied.helios.core.provider.StreamErrorMessage;
import com.standardapplied.helios.gemini.api.GeminiJson;
import com.standardapplied.helios.gemini.api.InteractionResponse;
import com.standardapplied.helios.gemini.api.InteractionUsage;
import com.standardapplied.helios.gemini.api.StreamingEvent;
import java.util.Map;

/**
 * Parses one Interactions API stream, one event at a time, into {@link StreamEvent}s: the step
 * events through {@link StreamedSteps}, the interaction's id and usage from its envelope and status
 * updates, and the assembled response at {@code interaction.completed}. The stream succeeds only
 * there, when that event's own interaction status is one the assembler counts as an ending and no
 * function call is still open; any other status, an unreadable event and function arguments that
 * are not a JSON object end it in an error, and an {@code error} event in one whose {@link
 * GeminiException} carries the reported code.
 */
final class GeminiStreamParser implements SseReader.Parser {

  private final boolean propagateInteractionId;
  private final String apiVersion;
  private final StreamedSteps steps = new StreamedSteps();
  private Response.Usage usage;
  private String interactionId;

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
    } catch (ProviderException e) {
      return failed(e.getMessage(), e);
    } catch (RuntimeException e) {
      return failed("Failed to parse stream event", e);
    }
  }

  @Override
  public Response<Void> partial() {
    return response(FinishReason.ERROR);
  }

  private StreamEvent on(StreamingEvent event) {
    return switch (event.eventType()) {
      case "error" -> error(event.error());
      case "interaction.created" -> envelope(event.interaction());
      case "interaction.completed" -> completed(event.interaction());
      case "interaction.status_update", "interaction.in_progress", "interaction.requires_action" ->
          checkpoint(event.interactionId(), event.usage());
      case "step.start" -> steps.start(event.index(), event.step());
      case "step.delta" -> steps.delta(event.index(), event.argumentsDelta(), event.delta());
      case "step.stop" -> steps.stop(event.index());
      case null, default -> null;
    };
  }

  private StreamEvent error(Map<String, Object> error) {
    var fields = error == null ? Map.<String, Object>of() : error;
    var code = fields.get("code");
    var message =
        StreamErrorMessage.of(
            "API error",
            code == null ? null : code.toString(),
            fields.get("message") == null ? null : fields.get("message").toString());
    var statusCode = code instanceof Number number && number.intValue() > 0 ? number.intValue() : 0;
    return failed(message, new GeminiException(message, statusCode));
  }

  private StreamEvent completed(InteractionResponse interaction) {
    envelope(interaction);
    var open = steps.openCall();
    if (open != null) {
      return failed("Interaction completed while function call '" + open + "' is still open", null);
    }
    var status = interaction == null ? null : interaction.status();
    var finishReason =
        GeminiResponseAssembler.finishReason(status, !steps.result().calls().isEmpty());
    if (finishReason == null) {
      return failed("Interaction completed with status " + status, null);
    }
    return new StreamEvent.Done(response(finishReason));
  }

  private StreamEvent failed(String message, Exception cause) {
    return new StreamEvent.Error(message, cause, partial());
  }

  private Response<Void> response(FinishReason finishReason) {
    return GeminiResponseAssembler.response(
        steps.result(),
        usage,
        propagateInteractionId ? interactionId : null,
        apiVersion,
        finishReason);
  }

  private StreamEvent envelope(InteractionResponse interaction) {
    return interaction == null ? null : checkpoint(interaction.id(), interaction.usage());
  }

  private StreamEvent checkpoint(String id, InteractionUsage reported) {
    if (id != null) {
      interactionId = id;
    }
    if (reported != null) {
      usage = GeminiResponseAssembler.usage(reported);
    }
    return null;
  }
}
