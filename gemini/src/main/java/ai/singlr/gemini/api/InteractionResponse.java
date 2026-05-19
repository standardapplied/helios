/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini.api;

import java.util.List;

/**
 * Response from the Gemini Interactions API ({@code Api-Revision: 2026-05-20}).
 *
 * <p>The legacy flat {@code outputs[]} array is replaced by a structured {@code steps[]} timeline.
 * {@code POST /interactions} returns only output steps; {@code GET /interactions/&#123;id&#125;}
 * returns the full timeline including the initial {@code user_input} step.
 *
 * @param id unique interaction identifier
 * @param model the model used
 * @param status interaction status ({@code in_progress}, {@code requires_action}, {@code
 *     completed}, {@code failed}, {@code cancelled})
 * @param steps the structured step timeline
 * @param usage token usage statistics
 */
public record InteractionResponse(
    String id, String model, String status, List<Step> steps, InteractionUsage usage) {

  public boolean hasStatusCompleted() {
    return "completed".equals(status);
  }

  public boolean hasStatusFailed() {
    return "failed".equals(status);
  }

  public boolean hasStatusRequiresAction() {
    return "requires_action".equals(status);
  }
}
