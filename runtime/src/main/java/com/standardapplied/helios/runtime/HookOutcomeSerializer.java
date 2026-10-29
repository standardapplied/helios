/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import com.standardapplied.helios.session.hooks.HookOutcome;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * Writes a {@link HookOutcome} as an object holding its {@code kind}, the record's simple name, and
 * the record's components, e.g. {@code {"kind":"Block","reason":"no writes"}}, except a {@link
 * HookOutcome.MutateHistory}, whose history is summarised as its count of {@code messages}.
 */
final class HookOutcomeSerializer extends StdSerializer<HookOutcome> {

  HookOutcomeSerializer() {
    super(HookOutcome.class);
  }

  @Override
  public void serialize(HookOutcome outcome, JsonGenerator gen, SerializationContext context) {
    gen.writeStartObject();
    gen.writeStringProperty("kind", outcome.getClass().getSimpleName());
    switch (outcome) {
      case HookOutcome.Continue _ -> {}
      case HookOutcome.MutateArgs mutate -> gen.writePOJOProperty("args", mutate.args());
      case HookOutcome.MutateHistory mutate ->
          gen.writeNumberProperty("messages", mutate.history().size());
      case HookOutcome.MutateText mutate -> gen.writeStringProperty("text", mutate.text());
      case HookOutcome.MutateResult mutate -> gen.writeStringProperty("output", mutate.output());
      case HookOutcome.Block block -> gen.writeStringProperty("reason", block.reason());
      case HookOutcome.Inject inject ->
          gen.writeStringProperty("userMessage", inject.userMessage());
      case HookOutcome.Stop stop -> gen.writeStringProperty("result", stop.result());
    }
    gen.writeEndObject();
  }
}
