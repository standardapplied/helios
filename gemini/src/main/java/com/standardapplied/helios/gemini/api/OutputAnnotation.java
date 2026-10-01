/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * An annotation on an output item from the Interactions API.
 *
 * <p>Used for Google Search grounding citations ({@code url_citation} type).
 *
 * @param type the annotation type (e.g., "url_citation")
 * @param url the cited URL
 * @param title the title of the cited source
 * @param startIndex character offset where the citation begins in the output text
 * @param endIndex character offset where the citation ends in the output text
 */
public record OutputAnnotation(
    String type,
    String url,
    String title,
    @JsonProperty("start_index") Integer startIndex,
    @JsonProperty("end_index") Integer endIndex) {}
