/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import com.standardapplied.helios.core.tool.ToolOutcome;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * Writes a {@link ToolOutcome} as an object holding its {@code kind}, the record's simple name, and
 * the record's components, e.g. {@code {"kind":"TimedOut","limit":"PT1M"}}. Core stays free of
 * Jackson, so the runtime registers this serializer on its one fixed mapper, {@link
 * JsonBody#MAPPER}.
 */
final class ToolOutcomeSerializer extends StdSerializer<ToolOutcome> {

  ToolOutcomeSerializer() {
    super(ToolOutcome.class);
  }

  @Override
  public void serialize(ToolOutcome outcome, JsonGenerator gen, SerializationContext context) {
    gen.writeStartObject();
    gen.writeStringProperty("kind", outcome.getClass().getSimpleName());
    switch (outcome) {
      case ToolOutcome.Succeeded succeeded -> {}
      case ToolOutcome.Failed failed -> {}
      case ToolOutcome.NotFound notFound -> {}
      case ToolOutcome.Threw threw ->
          gen.writeStringProperty("exceptionType", threw.exceptionType());
      case ToolOutcome.TimedOut timedOut ->
          gen.writeStringProperty("limit", timedOut.limit().toString());
      case ToolOutcome.Unknown unknown ->
          gen.writeStringProperty("limit", unknown.limit().toString());
      case ToolOutcome.Rejected rejected -> {
        gen.writeStringProperty("by", rejected.by());
        gen.writeStringProperty("reason", rejected.reason());
      }
    }
    gen.writeEndObject();
  }
}
