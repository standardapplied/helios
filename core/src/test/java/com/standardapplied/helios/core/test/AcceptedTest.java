/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.IteratorPublisher;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.schema.StructuredOutputParseException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.opentest4j.TestAbortedException;

class AcceptedTest {

  private static Response<Void> reply(String content, FinishReason finishReason) {
    return Response.newBuilder()
        .withContent(content)
        .withFinishReason(finishReason)
        .withUsage(Response.Usage.of(3, 2))
        .build();
  }

  private static final ModelChunk.MessageStop STOP =
      new ModelChunk.MessageStop("STOP", Response.Usage.of(3, 2), Map.of("k", "v"));

  private static Flow.Publisher<ModelChunk> chunks(ModelChunk... chunks) {
    return new IteratorPublisher<>(
        () -> CloseableIterator.of(List.of(chunks).iterator()), new CancellationToken());
  }

  @ParameterizedTest
  @EnumSource(
      value = FinishReason.class,
      names = {"STOP", "LENGTH", "REFUSAL", "CONTENT_FILTER"})
  void aTextReplyMayEndForAnyReasonThatOffersNoTool(FinishReason finishReason) {
    var response = reply("", finishReason);

    assertSame(response, Accepted.textReply(response));
  }

  @ParameterizedTest
  @EnumSource(
      value = FinishReason.class,
      names = {"TOOL_CALLS", "ERROR"})
  void aTextReplyMayNotEndInAToolCallOrAnError(FinishReason finishReason) {
    assertThrows(AssertionError.class, () -> Accepted.textReply(reply("", finishReason)));
  }

  @Test
  void aTextReplyCarriesUsage() {
    var response =
        Response.newBuilder().withContent("").withFinishReason(FinishReason.STOP).build();

    assertThrows(AssertionError.class, () -> Accepted.textReply(response));
  }

  @Test
  void aTextReplyCarriesContent() {
    var response =
        Response.newBuilder()
            .withFinishReason(FinishReason.STOP)
            .withUsage(Response.Usage.of(3, 2))
            .build();

    assertThrows(AssertionError.class, () -> Accepted.textReply(response));
  }

  private static Response.Builder<Void> calling(FinishReason finishReason) {
    var call = ToolCall.newBuilder().withId("c1").withName("t").withArguments(Map.of()).build();
    return Response.newBuilder().withToolCalls(List.of(call)).withFinishReason(finishReason);
  }

  private static Response<Void> called(FinishReason finishReason) {
    return calling(finishReason).withContent("").withUsage(Response.Usage.of(3, 2)).build();
  }

  @Test
  void aToolTurnThatCalledAToolCarriesContentAndUsage() {
    var withoutUsage = calling(FinishReason.TOOL_CALLS).withContent("").build();
    var withoutContent =
        calling(FinishReason.TOOL_CALLS).withUsage(Response.Usage.of(3, 2)).build();

    assertThrows(AssertionError.class, () -> Accepted.toolTurn(withoutUsage));
    assertThrows(AssertionError.class, () -> Accepted.toolTurn(withoutContent));
  }

  @Test
  void aToolTurnEitherCalledAToolOrIsATextReply() {
    var called = called(FinishReason.TOOL_CALLS);
    var answered = reply("done", FinishReason.STOP);

    assertSame(called, Accepted.toolTurn(called));
    assertSame(answered, Accepted.toolTurn(answered));
    assertThrows(AssertionError.class, () -> Accepted.toolTurn(reply("", FinishReason.TOOL_CALLS)));
  }

  @ParameterizedTest
  @EnumSource(
      value = FinishReason.class,
      names = {"LENGTH", "REFUSAL"})
  void aToolTurnCutShortMayCarryTheToolCallsEmittedBeforeIt(FinishReason finishReason) {
    var truncated = called(finishReason);

    assertSame(truncated, Accepted.toolTurn(truncated));
  }

  @Test
  void aToolTurnWithToolCallsMayNotEndInAnError() {
    assertThrows(AssertionError.class, () -> Accepted.toolTurn(called(FinishReason.ERROR)));
  }

  @Test
  void aParsedReplyIsReturned() {
    var response =
        Response.newBuilder(String.class)
            .withContent("{}")
            .withParsed("value")
            .withFinishReason(FinishReason.STOP)
            .withUsage(Response.Usage.of(3, 2))
            .build();

    assertSame(response, Accepted.parsedOrSkip("m", "SomeTest", () -> response));
  }

  @Test
  void aBlankReplyThatParsesToNoValueAbortsTheTestNamingItsCounterpart() {
    var aborted =
        assertThrows(
            TestAbortedException.class,
            () -> Accepted.parsedOrSkip("m", "SomeTest", () -> reply("", FinishReason.REFUSAL)));

    assertTrue(aborted.getMessage().contains("m wrote no JSON matching the schema; SomeTest"));
  }

  @Test
  void aReplyThatDoesNotParseAbortsTheTestNamingItsCounterpart() {
    var aborted =
        assertThrows(
            TestAbortedException.class,
            () ->
                Accepted.parsedOrSkip(
                    "m",
                    "SomeTest",
                    () -> {
                      throw new StructuredOutputParseException(List.of("bad"), "not json");
                    }));

    assertTrue(aborted.getMessage().contains("m wrote no JSON matching the schema; SomeTest"));
  }

  @Test
  void aStreamReturnsTheResponseItsChunksAddUpTo() {
    var call = ToolCall.newBuilder().withId("c1").withName("lookup").build();

    var response =
        Accepted.stream(
            chunks(
                new ModelChunk.TextDelta("Hello"),
                new ModelChunk.ThinkingDelta("hm"),
                new ModelChunk.TextDelta(" there"),
                new ModelChunk.ToolUseStop(call),
                STOP));

    assertEquals("Hello there", response.content());
    assertEquals(List.of(call), response.toolCalls());
    assertEquals(FinishReason.STOP, response.finishReason());
    assertEquals(STOP.usage(), response.usage());
    assertEquals(STOP.metadata(), response.metadata());
  }

  @Test
  void aStreamThatFailsFails() {
    var failing =
        new IteratorPublisher<ModelChunk>(
            () -> {
              throw new IllegalStateException("overloaded");
            },
            new CancellationToken());

    assertThrows(AssertionError.class, () -> Accepted.stream(failing));
  }

  @Test
  void aStreamWithoutAStopFails() {
    var stream = chunks(new ModelChunk.TextDelta("Hello"));

    assertThrows(AssertionError.class, () -> Accepted.stream(stream));
  }

  @Test
  void aStreamWithAChunkAfterTheStopFails() {
    var stream = chunks(STOP, new ModelChunk.TextDelta("x"));

    assertThrows(AssertionError.class, () -> Accepted.stream(stream));
  }

  @Test
  void aStreamWithTwoStopsFails() {
    var stream = chunks(STOP, STOP);

    assertThrows(AssertionError.class, () -> Accepted.stream(stream));
  }
}
