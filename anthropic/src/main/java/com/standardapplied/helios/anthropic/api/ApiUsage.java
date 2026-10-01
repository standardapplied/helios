/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.anthropic.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Token usage statistics from the Claude Messages API.
 *
 * @param inputTokens number of input tokens
 * @param outputTokens number of output tokens
 * @param cacheCreationInputTokens tokens used creating prompt cache entries
 * @param cacheReadInputTokens tokens read from prompt cache
 */
public record ApiUsage(
    @JsonProperty("input_tokens") Integer inputTokens,
    @JsonProperty("output_tokens") Integer outputTokens,
    @JsonProperty("cache_creation_input_tokens") Integer cacheCreationInputTokens,
    @JsonProperty("cache_read_input_tokens") Integer cacheReadInputTokens) {}
