/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.openai.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * SSE event envelope from the OpenAI Responses API streaming.
 *
 * <p>The {@code type} field discriminates between event kinds. Only {@code data:} lines carry
 * payload (simpler than Anthropic's paired {@code event:}/{@code data:} format).
 *
 * @param type event type discriminator
 * @param outputIndex index of the output item
 * @param contentIndex index of the content part within the output item
 * @param itemId the output item ID
 * @param item the output item (for output_item.added/done)
 * @param part the content part (for content_part.added/done)
 * @param delta text delta string
 * @param callId function call ID (for function_call_arguments events)
 * @param name function name (for function_call_arguments events)
 * @param arguments function call arguments (for function_call_arguments.done)
 * @param response the full response object (for response.completed/incomplete/failed)
 * @param text the full text of a completed reasoning summary part
 * @param summaryIndex the index of a reasoning summary part within its reasoning item
 * @param refusal the full text of a completed refusal
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiStreamEvent(
    String type,
    @JsonProperty("output_index") Integer outputIndex,
    @JsonProperty("content_index") Integer contentIndex,
    @JsonProperty("item_id") String itemId,
    OutputItem item,
    ContentPart part,
    String delta,
    @JsonProperty("call_id") String callId,
    String name,
    String arguments,
    ResponsesResponse response,
    String text,
    @JsonProperty("summary_index") Integer summaryIndex,
    String refusal) {}
