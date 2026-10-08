/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.anthropic.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Output-side configuration for the Claude Messages API. Sibling field of {@code thinking}; pairs
 * with {@link ThinkingConfig#adaptive(String)} to set thinking strength. Omitted, an adaptive model
 * runs the API's default effort.
 *
 * @param effort {@code "low"}, {@code "medium"}, {@code "high"}, {@code "xhigh"} or {@code "max"};
 *     {@code null} omits
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OutputConfig(String effort) {}
