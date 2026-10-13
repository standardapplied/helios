/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.openai.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Response body from the OpenAI Responses API.
 *
 * @param id response ID
 * @param object always "response"
 * @param status response status: "completed", "failed", "incomplete", "in_progress"
 * @param output list of output items (messages, function calls, reasoning)
 * @param model the model used
 * @param usage token usage statistics
 * @param incompleteDetails why an {@code incomplete} response stopped, or {@code null}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResponsesResponse(
    String id,
    String object,
    String status,
    List<OutputItem> output,
    String model,
    ApiUsage usage,
    @JsonProperty("incomplete_details") IncompleteDetails incompleteDetails) {

  /**
   * Why a response finished {@code incomplete}.
   *
   * @param reason {@code max_output_tokens}, {@code content_filter}, {@code max_messages} or {@code
   *     steered}
   */
  public record IncompleteDetails(String reason) {}
}
