/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A streaming event from the Interactions API SSE stream ({@code Api-Revision: 2026-05-20}).
 *
 * <p>The legacy {@code interaction.start} / {@code content.start} / {@code content.delta} / {@code
 * content.stop} / {@code interaction.complete} / {@code interaction.status_update} family is
 * replaced by step-scoped events:
 *
 * <table>
 *   <caption>Streaming event types</caption>
 *   <tr><th>Event type</th><th>Payload</th></tr>
 *   <tr><td>{@code interaction.created}</td><td>{@link #interaction()}</td></tr>
 *   <tr><td>{@code interaction.in_progress}</td><td>{@link #interactionId()}</td></tr>
 *   <tr><td>{@code interaction.requires_action}</td><td>{@link #interactionId()}</td></tr>
 *   <tr><td>{@code interaction.completed}</td><td>{@link #interaction()}</td></tr>
 *   <tr><td>{@code step.start}</td><td>{@link #index()}, {@link #step()}</td></tr>
 *   <tr><td>{@code step.delta}</td>
 *       <td>{@link #index()}, {@link #delta()} or {@link #argumentsDelta()}</td></tr>
 *   <tr><td>{@code step.stop}</td><td>{@link #index()}, {@link #status()}</td></tr>
 * </table>
 *
 * @param eventType the event type discriminator
 * @param index the step index this event refers to (step.* events)
 * @param step the step descriptor (only on {@code step.start})
 * @param delta incremental content (text or annotations) on {@code step.delta}
 * @param argumentsDelta partial JSON of function-call arguments on {@code step.delta}
 * @param interaction the full interaction object on {@code interaction.created} / {@code
 *     interaction.completed}
 * @param interactionId the interaction id on status-update events
 * @param status step status on {@code step.stop} (e.g. {@code done})
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StreamingEvent(
    @JsonProperty("event_type") String eventType,
    Integer index,
    Step step,
    ContentItem delta,
    @JsonProperty("arguments_delta") String argumentsDelta,
    InteractionResponse interaction,
    @JsonProperty("interaction_id") String interactionId,
    String status) {

  public boolean hasTypeInteractionCreated() {
    return "interaction.created".equals(eventType);
  }

  public boolean hasTypeInteractionCompleted() {
    return "interaction.completed".equals(eventType);
  }

  public boolean hasTypeInteractionInProgress() {
    return "interaction.in_progress".equals(eventType);
  }

  public boolean hasTypeInteractionRequiresAction() {
    return "interaction.requires_action".equals(eventType);
  }

  /**
   * The deployed {@code Api-Revision: 2026-05-20} server still emits the legacy unified {@code
   * interaction.status_update} event in place of the doc-promised {@code interaction.in_progress} /
   * {@code interaction.requires_action} split. Carries {@link #interactionId()} and {@link
   * #status()}.
   */
  public boolean hasTypeInteractionStatusUpdate() {
    return "interaction.status_update".equals(eventType);
  }

  public boolean hasTypeStepStart() {
    return "step.start".equals(eventType);
  }

  public boolean hasTypeStepDelta() {
    return "step.delta".equals(eventType);
  }

  public boolean hasTypeStepStop() {
    return "step.stop".equals(eventType);
  }
}
