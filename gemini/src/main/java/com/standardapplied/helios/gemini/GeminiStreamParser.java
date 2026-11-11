/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.ProviderException;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.model.TransientStreamException;
import com.standardapplied.helios.core.provider.SseReader;
import com.standardapplied.helios.core.provider.StreamErrorMessage;
import com.standardapplied.helios.gemini.api.GeminiJson;
import com.standardapplied.helios.gemini.api.InteractionResponse;
import com.standardapplied.helios.gemini.api.InteractionUsage;
import com.standardapplied.helios.gemini.api.StreamingEvent;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Parses one Interactions API stream, one event at a time, into {@link StreamEvent}s: the step
 * events through {@link StreamedSteps}, the interaction's id and usage from its envelope and status
 * updates, each usage as the usage so far, the model that served it from its envelope, and the
 * assembled response at {@code interaction.completed}. The stream succeeds only there, when that
 * event's own interaction status is one the assembler counts as an ending and no function call is
 * still open; any other status, an unreadable event and function arguments that are not a JSON
 * object end it in an error, and an {@code error} event in one whose {@link GeminiException}
 * carries the reported code. An error that names recitation is Gemini's recitation filter blocking
 * the response: it ends the stream with what streamed before it, finishing with {@link
 * FinishReason#CONTENT_FILTER} and Gemini's message as the refusal's explanation. A body that ends
 * early with a bare {@code {"error":...}} line ends as that error event would.
 */
final class GeminiStreamParser implements SseReader.Parser {

  private static final String RECITATION = "recitation";

  private final boolean propagateInteractionId;
  private final String apiVersion;
  private final String promptStamp;
  private final StreamedSteps steps = new StreamedSteps();
  private Response.Usage usage;
  private String interactionId;
  private String model;

  /**
   * A parser for a stream of the interactions API {@code apiVersion} answering a request built
   * under {@code promptStamp}, whose response records its interaction id when {@code
   * propagateInteractionId} is set, for server-side continuation.
   */
  GeminiStreamParser(boolean propagateInteractionId, String apiVersion, String promptStamp) {
    this.propagateInteractionId = propagateInteractionId;
    this.apiVersion = apiVersion;
    this.promptStamp = promptStamp;
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

  @Override
  public StreamEvent unframedLine(String line) {
    try {
      var event = GeminiJson.LENIENT.readValue(line, StreamingEvent.class);
      return event != null && event.eventType() == null && event.error() != null
          ? error(event.error())
          : null;
    } catch (RuntimeException e) {
      return null;
    }
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
    var reported = Objects.toString(fields.get("message"), null);
    var codeText = Objects.toString(code, null);
    if (namesRecitation(reported) || namesRecitation(codeText)) {
      return blocked(Objects.requireNonNullElse(reported, codeText));
    }
    var message = StreamErrorMessage.of("API error", codeText, reported);
    var statusCode = code instanceof Number number && number.intValue() > 0 ? number.intValue() : 0;
    var exception = new GeminiException(message, statusCode);
    return failed(
        message,
        exception.isRetryable()
            ? new TransientStreamException(message, exception, GeminiProvider.PROVIDER_NAME)
            : exception);
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

  /** The response so far, which Gemini's recitation filter stopped for {@code explanation}. */
  private StreamEvent blocked(String explanation) {
    return new StreamEvent.Done(
        response(
            FinishReason.CONTENT_FILTER,
            Map.of(
                Response.REFUSAL_EXPLANATION_KEY,
                explanation,
                Response.REFUSAL_CATEGORY_KEY,
                RECITATION)));
  }

  private static boolean namesRecitation(String text) {
    return text != null && text.toLowerCase(Locale.ROOT).contains(RECITATION);
  }

  private Response<Void> response(FinishReason finishReason) {
    return response(finishReason, Map.of());
  }

  private Response<Void> response(FinishReason finishReason, Map<String, String> refusal) {
    return GeminiResponseAssembler.response(
        steps.result(),
        usage,
        propagateInteractionId ? interactionId : null,
        model,
        apiVersion,
        promptStamp,
        finishReason,
        refusal);
  }

  private StreamEvent envelope(InteractionResponse interaction) {
    if (interaction == null) {
      return null;
    }
    if (!Strings.isBlank(interaction.model())) {
      model = interaction.model();
    }
    return checkpoint(interaction.id(), interaction.usage());
  }

  /**
   * Records the interaction's {@code id} and the usage it {@code reported}, if any; the usage so
   * far when it reported some that counts a token, else {@code null}.
   */
  private StreamEvent checkpoint(String id, InteractionUsage reported) {
    if (id != null) {
      interactionId = id;
    }
    if (reported == null) {
      return null;
    }
    usage = GeminiResponseAssembler.usage(reported);
    return usage.reportsTokens() ? new StreamEvent.UsageDelta(usage) : null;
  }
}
