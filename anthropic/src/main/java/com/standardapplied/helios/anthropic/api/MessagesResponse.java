/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.anthropic.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Response from the Claude Messages API.
 *
 * @param id message ID
 * @param type always "message"
 * @param role always "assistant"
 * @param content list of content blocks (text, tool_use, thinking)
 * @param model the model that generated the response
 * @param stopReason why generation stopped: "end_turn", "max_tokens", "stop_sequence", "tool_use"
 * @param stopSequence the stop sequence that was matched (if applicable)
 * @param usage token usage statistics
 */
public record MessagesResponse(
    String id,
    String type,
    String role,
    List<ContentBlock> content,
    String model,
    @JsonProperty("stop_reason") String stopReason,
    @JsonProperty("stop_sequence") String stopSequence,
    ApiUsage usage) {}
