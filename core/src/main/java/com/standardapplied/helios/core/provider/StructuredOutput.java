/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.schema.RawOutputCapturePolicy;
import com.standardapplied.helios.core.schema.StructuredContentParser;

/**
 * Types a finished turn by its output schema: the content is parsed and validated through the
 * provider's JSON adapter, unless the turn called tools. A tool-calling turn is intermediate: its
 * prose is not the structured answer, which a later text-only turn delivers.
 *
 * @param json the provider's JSON adapter
 * @param capturePolicy whether a parse failure keeps the raw output
 */
record StructuredOutput(
    StructuredContentParser.JsonAdapter json, RawOutputCapturePolicy capturePolicy) {

  <T> Response<T> of(Response<Void> response, OutputSchema<T> schema) {
    var parsed =
        response.toolCalls().isEmpty()
            ? StructuredContentParser.parse(response.content(), schema, json, capturePolicy)
            : null;
    return Response.<T>newBuilder(schema.type())
        .withContent(response.content())
        .withParsed(parsed)
        .withToolCalls(response.toolCalls())
        .withFinishReason(response.finishReason())
        .withUsage(response.usage())
        .withThinking(response.thinking())
        .withCitations(response.citations())
        .withMetadata(response.metadata())
        .build();
  }
}
