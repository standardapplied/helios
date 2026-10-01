/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.openai.api;

import com.fasterxml.jackson.annotation.JsonInclude;
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
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResponsesResponse(
    String id,
    String object,
    String status,
    List<OutputItem> output,
    String model,
    ApiUsage usage) {}
