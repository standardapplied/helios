/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.schema.StructuredOutputParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Assumptions;

/**
 * Assertions a live test makes on what a provider sent back, which hold for any output the model
 * may produce: the provider accepted the request, and Helios turned its reply into Helios types.
 */
public final class Accepted {

  private static final Set<FinishReason> TEXT_FINISHES =
      EnumSet.of(
          FinishReason.STOP,
          FinishReason.LENGTH,
          FinishReason.REFUSAL,
          FinishReason.CONTENT_FILTER);

  private Accepted() {}

  /**
   * Asserts that {@code response}, the reply to a request that offers no tool, ended for a reason
   * that request allows and carries content and usage, and returns it.
   */
  public static <T> Response<T> textReply(Response<T> response) {
    carriesContentAndUsage(response);
    assertTrue(
        TEXT_FINISHES.contains(response.finishReason()),
        () -> "finish reason " + response.finishReason());
    return response;
  }

  /**
   * Asserts that {@code response}, the reply to a request that offers tools, either ended to call a
   * tool and carries that call or is a {@link #textReply text reply}, and returns it. A reply cut
   * short by the token limit or a refusal may carry the tool calls emitted before it stopped.
   */
  public static <T> Response<T> toolTurn(Response<T> response) {
    if (response.finishReason() != FinishReason.TOOL_CALLS) {
      return textReply(response);
    }
    carriesContentAndUsage(response);
    assertTrue(response.hasToolCalls(), "TOOL_CALLS must carry a tool call");
    return response;
  }

  private static void carriesContentAndUsage(Response<?> response) {
    assertNotNull(response.content(), "content");
    assertNotNull(response.usage(), "usage");
  }

  /**
   * The parsed reply {@code call} returns, or an aborted test when the model wrote output that does
   * not parse against the schema, for a provider that does not constrain its output to the schema:
   * a parse is then the model's choice, and {@code counterpart}, a recorded test, proves the
   * parsing. A blank reply parses to no value without an exception, so it aborts too.
   */
  public static <T> Response<T> parsedOrSkip(
      String modelId, String counterpart, Supplier<Response<T>> call) {
    var unparsed =
        modelId + " wrote no JSON matching the schema; " + counterpart + " covers the parsing";
    try {
      var response = call.get();
      Assumptions.assumeTrue(response.hasParsed(), unparsed);
      return response;
    } catch (StructuredOutputParseException mismatch) {
      return Assumptions.abort(unparsed);
    }
  }

  /**
   * Reads {@code chunks} to their end, asserting that the stream ends without an error and in
   * exactly one {@link ModelChunk.MessageStop}; returns the response the chunks add up to: the text
   * deltas joined, the completed tool calls, and the stop's finish reason, usage, metadata and
   * citations.
   */
  public static Response<Void> stream(CloseableIterator<ModelChunk> chunks) {
    var read = StreamRead.of(chunks);
    if (read.failure() != null) {
      throw new AssertionError("the chunk stream failed", read.failure());
    }
    var received = read.items();
    var stops = received.stream().filter(ModelChunk.MessageStop.class::isInstance).toList();
    assertEquals(1, stops.size(), () -> "message stops in " + received);
    var stop = assertInstanceOf(ModelChunk.MessageStop.class, received.getLast(), "last chunk");
    var text = new StringBuilder();
    var toolCalls = new ArrayList<ToolCall>();
    for (var chunk : received) {
      switch (chunk) {
        case ModelChunk.TextDelta(var delta) -> text.append(delta);
        case ModelChunk.ToolUseStop(var call) -> toolCalls.add(call);
        default -> {}
      }
    }
    return Response.newBuilder()
        .withContent(text.toString())
        .withToolCalls(toolCalls)
        .withFinishReason(stop.finishReason())
        .withUsage(stop.usage())
        .withMetadata(stop.metadata())
        .withCitations(stop.citations())
        .build();
  }
}
