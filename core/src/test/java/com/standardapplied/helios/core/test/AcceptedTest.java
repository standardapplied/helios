/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.schema.StructuredOutputParseException;
import java.util.List;
import java.util.Map;
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

  private static CloseableIterator<StreamEvent> events(StreamEvent... events) {
    return CloseableIterator.of(List.of(events).iterator());
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
  void aStreamReturnsTheResponseItsDeltasJoinTo() {
    var done = new StreamEvent.Done(reply("Hello there", FinishReason.STOP));

    var response =
        Accepted.stream(
            events(new StreamEvent.TextDelta("Hello"), new StreamEvent.TextDelta(" there"), done));

    assertSame(done.response(), response);
  }

  @Test
  void aStreamWhoseDeltasDifferFromDoneFails() {
    var done = new StreamEvent.Done(reply("Hello\n\nthere", FinishReason.STOP));
    var stream =
        events(new StreamEvent.TextDelta("Hello"), new StreamEvent.TextDelta("there"), done);

    assertThrows(AssertionError.class, () -> Accepted.stream(stream));
  }

  @Test
  void aStreamWithAnErrorEventFails() {
    var stream =
        events(
            new StreamEvent.Error("overloaded", null),
            new StreamEvent.Done(reply("", FinishReason.STOP)));

    assertThrows(AssertionError.class, () -> Accepted.stream(stream));
  }

  @Test
  void aStreamWithoutDoneFails() {
    var stream = events(new StreamEvent.TextDelta("Hello"));

    assertThrows(AssertionError.class, () -> Accepted.stream(stream));
  }

  @Test
  void aStreamWithAnEventAfterDoneFails() {
    var stream =
        events(new StreamEvent.Done(reply("", FinishReason.STOP)), new StreamEvent.TextDelta("x"));

    assertThrows(AssertionError.class, () -> Accepted.stream(stream));
  }
}
