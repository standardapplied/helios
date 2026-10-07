/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import static com.standardapplied.helios.core.test.SseEvents.data;

import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.provider.SseReader;
import com.standardapplied.helios.core.test.SseEvents;
import java.io.InputStream;
import java.util.List;

/**
 * The wire text of Interactions API stream events, and the events the Gemini stream parser yields
 * from it.
 */
final class GeminiSse {

  static final String INTERACTION_CREATED =
      data(
          "{\"event_type\":\"interaction.created\","
              + "\"interaction\":{\"id\":\"int_xyz\",\"status\":\"in_progress\"}}");

  static final String INTERACTION_IN_PROGRESS =
      data("{\"event_type\":\"interaction.in_progress\",\"interaction_id\":\"int_xyz\"}");

  static final String INTERACTION_COMPLETED = interactionEnded("completed");

  static final String INTERACTION_COMPLETED_NO_USAGE =
      data(
          "{\"event_type\":\"interaction.completed\","
              + "\"interaction\":{\"id\":\"int_xyz\",\"status\":\"completed\"}}");

  static final String MODEL_OUTPUT_START = stepStart(0, "{\"type\":\"model_output\"}");
  static final String HELLO_DELTA = stepDelta(0, "{\"type\":\"text\",\"text\":\"Hello\"}");
  static final String MODEL_OUTPUT_STOP = stepStop(0);

  /** A model output step streaming "Hello", then the completed interaction. */
  static final String TEXT_FLOW =
      MODEL_OUTPUT_START + HELLO_DELTA + MODEL_OUTPUT_STOP + INTERACTION_COMPLETED;

  private GeminiSse() {}

  /** Interaction {@code int_xyz} ending with {@code status}, having used 10 + 5 tokens. */
  static String interactionEnded(String status) {
    return data(
        "{\"event_type\":\"interaction.completed\","
            + "\"interaction\":{\"id\":\"int_xyz\",\"status\":\""
            + status
            + "\",\"usage\":{\"total_input_tokens\":10,\"total_output_tokens\":5,"
            + "\"total_tokens\":15}}}");
  }

  /** The {@code step.start} event opening step {@code index}, described by {@code stepJson}. */
  static String stepStart(int index, String stepJson) {
    return data(
        "{\"event_type\":\"step.start\",\"index\":" + index + ",\"step\":" + stepJson + "}");
  }

  /** A {@code step.delta} event carrying {@code deltaJson} for step {@code index}. */
  static String stepDelta(int index, String deltaJson) {
    return data(
        "{\"event_type\":\"step.delta\",\"index\":" + index + ",\"delta\":" + deltaJson + "}");
  }

  /**
   * A {@code step.delta} event carrying a fragment of step {@code index}'s function call arguments.
   *
   * @param escapedJson the fragment, already escaped to sit inside a JSON string
   */
  static String stepArgumentsDelta(int index, String escapedJson) {
    return data(
        "{\"event_type\":\"step.delta\",\"index\":"
            + index
            + ",\"arguments_delta\":\""
            + escapedJson
            + "\"}");
  }

  /** The {@code step.stop} event closing step {@code index}. */
  static String stepStop(int index) {
    return data("{\"event_type\":\"step.stop\",\"index\":" + index + ",\"status\":\"done\"}");
  }

  /** A reader of {@code body} through the stateful parser of the default API version. */
  static SseReader reader(InputStream body) {
    return new SseReader(
        body,
        SseEvents.NEVER_IDLE,
        new GeminiStreamParser(true, GeminiEndpoint.DEFAULT_API_VERSION),
        GeminiException::new);
  }

  /** Every event the stateful parser yields from {@code sse}. */
  static List<StreamEvent> drain(String sse) {
    return SseEvents.drain(reader(SseEvents.body(sse)));
  }
}
