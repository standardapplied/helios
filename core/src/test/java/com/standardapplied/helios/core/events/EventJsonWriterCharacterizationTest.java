/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.events.HeliosEvent.SessionEnd.Termination;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.core.trace.Span;
import com.standardapplied.helios.core.trace.Trace;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins the exact bytes {@link EventJsonWriter} emits for every {@link HeliosEvent} subtype in both
 * encodings, so a restructuring of the writer cannot change a single character of the JSONL output.
 */
class EventJsonWriterCharacterizationTest {

  private static final Instant AT = Instant.parse("2026-05-13T10:00:00Z");
  private static final UUID RUN = uuid(1);
  private static final Optional<UUID> NO_SPAN = Optional.empty();

  private static UUID uuid(int n) {
    return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", n));
  }

  private static Trace bareTrace() {
    return Trace.newBuilder().withId(uuid(10)).withTotalTokens(42).build();
  }

  private static Trace fullTrace() {
    return Trace.newBuilder()
        .withId(uuid(11))
        .withDuration(Duration.ofMillis(5))
        .withSpan(Span.newBuilder().withId(uuid(12)).withName("model.chat").build())
        .withModelId("m-1")
        .withAttribute("gemini.apiVersion", "v1beta")
        .withUsage(new Usage(10, 20, 3, 4, 37))
        .build();
  }

  static Stream<Arguments> cases() {
    var span = Optional.of(uuid(2));
    var session = uuid(3);
    var messages = List.of(Message.user("hi"), Message.assistant("yo"));
    return Stream.of(
        Arguments.of(
            new HeliosEvent.RunStarted(AT, RUN, span, "agent", Map.of("k\"1", "v\n1")),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":\"00000000-0000-0000-0000-000000000002\",\"type\":\"RunStarted\",\"harnessKind\":\"agent\",\"attributes\":{\"k\\\"1\":\"v\\n1\"}}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":\"00000000-0000-0000-0000-000000000002\",\"type\":\"RunStarted\"}"),
        Arguments.of(
            new HeliosEvent.RunStarted(
                AT, RUN, NO_SPAN, "agent", Map.of("gemini.apiVersion", "v1")),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"RunStarted\",\"harnessKind\":\"agent\",\"attributes\":{\"gemini.apiVersion\":\"v1\"}}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"RunStarted\",\"apiVersion\":\"v1\"}"),
        Arguments.of(
            new HeliosEvent.RunStarted(AT, RUN, NO_SPAN, "agent", Map.of("apiVersion", "custom")),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"RunStarted\",\"harnessKind\":\"agent\",\"attributes\":{\"apiVersion\":\"custom\"}}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"RunStarted\",\"apiVersion\":\"custom\"}"),
        Arguments.of(
            new HeliosEvent.RunStarted(AT, RUN, NO_SPAN, "agent", Map.of("apiVersion", "v2")),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"RunStarted\",\"harnessKind\":\"agent\",\"attributes\":{\"apiVersion\":\"v2\"}}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"RunStarted\"}"),
        Arguments.of(
            new HeliosEvent.RunStarted(AT, RUN, NO_SPAN, "agent", Map.of()),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"RunStarted\",\"harnessKind\":\"agent\",\"attributes\":{}}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"RunStarted\"}"),
        Arguments.of(
            new HeliosEvent.RunCompleted(AT, RUN, NO_SPAN, bareTrace()),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"RunCompleted\",\"trace\":{\"id\":\"00000000-0000-0000-0000-000000000010\",\"durationNanos\":null,\"spanCount\":0,\"totalTokens\":42}}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"RunCompleted\",\"trace\":{\"id\":\"00000000-0000-0000-0000-000000000010\",\"spanCount\":0,\"success\":true,\"totalTokens\":42}}"),
        Arguments.of(
            new HeliosEvent.RunCompleted(AT, RUN, span, fullTrace()),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":\"00000000-0000-0000-0000-000000000002\",\"type\":\"RunCompleted\",\"trace\":{\"id\":\"00000000-0000-0000-0000-000000000011\",\"durationNanos\":5000000,\"spanCount\":1,\"totalTokens\":0}}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":\"00000000-0000-0000-0000-000000000002\",\"type\":\"RunCompleted\",\"trace\":{\"id\":\"00000000-0000-0000-0000-000000000011\",\"durationNanos\":5000000,\"spanCount\":1,\"success\":true,\"modelId\":\"m-1\",\"apiVersion\":\"v1beta\",\"inputTokens\":10,\"outputTokens\":20,\"cacheCreationInputTokens\":3,\"cacheReadInputTokens\":4,\"totalTokens\":37}}"),
        Arguments.of(
            new HeliosEvent.RunFailed(AT, RUN, NO_SPAN, "boom", bareTrace()),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"RunFailed\",\"error\":\"boom\",\"trace\":{\"id\":\"00000000-0000-0000-0000-000000000010\",\"durationNanos\":null,\"spanCount\":0,\"totalTokens\":42}}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"RunFailed\",\"errorCategory\":\"run_failed\",\"trace\":{\"id\":\"00000000-0000-0000-0000-000000000010\",\"spanCount\":0,\"success\":false,\"totalTokens\":42}}"),
        Arguments.of(
            new HeliosEvent.RunFailed(AT, RUN, NO_SPAN, "boom", fullTrace()),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"RunFailed\",\"error\":\"boom\",\"trace\":{\"id\":\"00000000-0000-0000-0000-000000000011\",\"durationNanos\":5000000,\"spanCount\":1,\"totalTokens\":0}}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"RunFailed\",\"errorCategory\":\"run_failed\",\"trace\":{\"id\":\"00000000-0000-0000-0000-000000000011\",\"durationNanos\":5000000,\"spanCount\":1,\"success\":false,\"modelId\":\"m-1\",\"apiVersion\":\"v1beta\",\"inputTokens\":10,\"outputTokens\":20,\"cacheCreationInputTokens\":3,\"cacheReadInputTokens\":4,\"totalTokens\":37}}"),
        Arguments.of(
            new HeliosEvent.IterationStarted(AT, RUN, NO_SPAN, 2, 9),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"IterationStarted\",\"iteration\":2,\"maxIterations\":9}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"IterationStarted\",\"iteration\":2,\"maxIterations\":9}"),
        Arguments.of(
            new HeliosEvent.IterationCompleted(AT, RUN, NO_SPAN, 2),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"IterationCompleted\",\"iteration\":2}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"IterationCompleted\",\"iteration\":2}"),
        Arguments.of(
            new HeliosEvent.BeforeApiCall(AT, RUN, NO_SPAN, "u-1", session, messages, 3),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"BeforeApiCall\",\"userId\":\"u-1\",\"sessionId\":\"00000000-0000-0000-0000-000000000003\",\"messageCount\":2,\"iteration\":3}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"BeforeApiCall\",\"messageCount\":2,\"iteration\":3}"),
        Arguments.of(
            new HeliosEvent.BeforeApiCall(AT, RUN, NO_SPAN, null, null, List.of(), 0),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"BeforeApiCall\",\"userId\":null,\"sessionId\":\"\",\"messageCount\":0,\"iteration\":0}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"BeforeApiCall\",\"messageCount\":0,\"iteration\":0}"),
        Arguments.of(
            new HeliosEvent.AfterTurn(
                AT,
                RUN,
                NO_SPAN,
                "u-1",
                session,
                Optional.empty(),
                Message.assistant("ok"),
                List.of(Message.tool("c-1", "t", "r")),
                1),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"AfterTurn\",\"userId\":\"u-1\",\"sessionId\":\"00000000-0000-0000-0000-000000000003\",\"toolMessageCount\":1,\"iteration\":1}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"AfterTurn\",\"toolMessageCount\":1,\"iteration\":1}"),
        Arguments.of(
            new HeliosEvent.AfterTurn(
                AT,
                RUN,
                NO_SPAN,
                null,
                null,
                Optional.empty(),
                Message.assistant("ok"),
                List.of(),
                1),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"AfterTurn\",\"userId\":null,\"sessionId\":\"\",\"toolMessageCount\":0,\"iteration\":1}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"AfterTurn\",\"toolMessageCount\":0,\"iteration\":1}"),
        Arguments.of(
            new HeliosEvent.BeforeCompaction(AT, RUN, NO_SPAN, "u-1", session, messages),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"BeforeCompaction\",\"userId\":\"u-1\",\"sessionId\":\"00000000-0000-0000-0000-000000000003\",\"messageCount\":2}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"BeforeCompaction\",\"messageCount\":2}"),
        Arguments.of(
            new HeliosEvent.BeforeCompaction(AT, RUN, NO_SPAN, null, null, List.of()),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"BeforeCompaction\",\"userId\":null,\"sessionId\":\"\",\"messageCount\":0}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"BeforeCompaction\",\"messageCount\":0}"),
        Arguments.of(
            new HeliosEvent.SessionEnd(
                AT, RUN, NO_SPAN, "u-1", session, messages, Termination.FAILED),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"SessionEnd\",\"userId\":\"u-1\",\"sessionId\":\"00000000-0000-0000-0000-000000000003\",\"termination\":\"FAILED\",\"finalMessageCount\":2}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"SessionEnd\",\"termination\":\"FAILED\",\"finalMessageCount\":2}"),
        Arguments.of(
            new HeliosEvent.SessionEnd(
                AT, RUN, NO_SPAN, null, null, List.of(), Termination.COMPLETED),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"SessionEnd\",\"userId\":null,\"sessionId\":\"\",\"termination\":\"COMPLETED\",\"finalMessageCount\":0}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"SessionEnd\",\"termination\":\"COMPLETED\",\"finalMessageCount\":0}"),
        Arguments.of(
            new HeliosEvent.AssistantTextDelta(AT, RUN, NO_SPAN, "q\"b\\\b\f\n\r\t\u0001\u001fé"),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"AssistantTextDelta\",\"text\":\"q\\\"b\\\\\\b\\f\\n\\r\\t\\u0001\\u001f\u00e9\"}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"AssistantTextDelta\"}"),
        Arguments.of(
            new HeliosEvent.AssistantText(AT, RUN, NO_SPAN, "full text"),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"AssistantText\",\"fullText\":\"full text\"}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"AssistantText\"}"),
        Arguments.of(
            new HeliosEvent.AssistantThinkingDelta(AT, RUN, NO_SPAN, "thinking"),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"AssistantThinkingDelta\",\"thinkingText\":\"thinking\"}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"AssistantThinkingDelta\"}"),
        Arguments.of(
            new HeliosEvent.AssistantThinkingComplete(AT, RUN, NO_SPAN, "all", Optional.of("sig")),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"AssistantThinkingComplete\",\"fullThinking\":\"all\",\"signature\":\"sig\"}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"AssistantThinkingComplete\"}"),
        Arguments.of(
            new HeliosEvent.AssistantThinkingComplete(AT, RUN, NO_SPAN, "all", Optional.empty()),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"AssistantThinkingComplete\",\"fullThinking\":\"all\",\"signature\":null}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"AssistantThinkingComplete\"}"),
        Arguments.of(
            new HeliosEvent.ToolCallStarted(AT, RUN, NO_SPAN, "c-1", "grep", Map.of("q", "x")),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"ToolCallStarted\",\"toolCallId\":\"c-1\",\"toolName\":\"grep\",\"args\":{\"q\":\"x\"}}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"ToolCallStarted\",\"toolCallId\":\"c-1\",\"toolName\":\"grep\"}"),
        Arguments.of(
            new HeliosEvent.ToolCallStarted(AT, RUN, NO_SPAN, "c-1", "grep", Map.of()),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"ToolCallStarted\",\"toolCallId\":\"c-1\",\"toolName\":\"grep\",\"args\":{}}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"ToolCallStarted\",\"toolCallId\":\"c-1\",\"toolName\":\"grep\"}"),
        Arguments.of(
            new HeliosEvent.ToolCallCompleted(
                AT, RUN, NO_SPAN, "c-1", ToolResult.success("out"), Duration.ofNanos(1500)),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"ToolCallCompleted\",\"toolCallId\":\"c-1\",\"success\":true,\"tookNanos\":1500}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"ToolCallCompleted\",\"toolCallId\":\"c-1\",\"success\":true,\"tookNanos\":1500}"),
        Arguments.of(
            new HeliosEvent.ToolCallCompleted(
                AT, RUN, NO_SPAN, "c-1", ToolResult.failure("bad"), Duration.ZERO),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"ToolCallCompleted\",\"toolCallId\":\"c-1\",\"success\":false,\"tookNanos\":0}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"ToolCallCompleted\",\"toolCallId\":\"c-1\",\"success\":false,\"tookNanos\":0}"),
        Arguments.of(
            new HeliosEvent.ToolCallFailed(AT, RUN, NO_SPAN, "c-1", "exploded"),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"ToolCallFailed\",\"toolCallId\":\"c-1\",\"error\":\"exploded\"}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"ToolCallFailed\",\"toolCallId\":\"c-1\",\"success\":false,\"errorCategory\":\"tool_failed\"}"),
        Arguments.of(
            new HeliosEvent.MemoryWritten(AT, RUN, NO_SPAN, "notes", "append"),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"MemoryWritten\",\"blockName\":\"notes\",\"operation\":\"append\"}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"MemoryWritten\"}"),
        Arguments.of(
            new HeliosEvent.MemoryRead(AT, RUN, NO_SPAN, "notes"),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"MemoryRead\",\"blockName\":\"notes\"}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"MemoryRead\"}"),
        Arguments.of(
            new HeliosEvent.SpanOpened(AT, RUN, NO_SPAN, uuid(4), Optional.of(uuid(5)), "tool.x"),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"SpanOpened\",\"openedSpanId\":\"00000000-0000-0000-0000-000000000004\",\"parentSpanId\":\"00000000-0000-0000-0000-000000000005\",\"name\":\"tool.x\"}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"SpanOpened\",\"openedSpanId\":\"00000000-0000-0000-0000-000000000004\",\"parentSpanId\":\"00000000-0000-0000-0000-000000000005\"}"),
        Arguments.of(
            new HeliosEvent.SpanOpened(AT, RUN, NO_SPAN, uuid(4), Optional.empty(), "tool.x"),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"SpanOpened\",\"openedSpanId\":\"00000000-0000-0000-0000-000000000004\",\"parentSpanId\":null,\"name\":\"tool.x\"}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"SpanOpened\",\"openedSpanId\":\"00000000-0000-0000-0000-000000000004\",\"parentSpanId\":null}"),
        Arguments.of(
            new HeliosEvent.SpanClosed(
                AT, RUN, NO_SPAN, uuid(4), Duration.ofMillis(2), true, Optional.empty()),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"SpanClosed\",\"closedSpanId\":\"00000000-0000-0000-0000-000000000004\",\"durationNanos\":2000000,\"success\":true,\"error\":null}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"SpanClosed\",\"closedSpanId\":\"00000000-0000-0000-0000-000000000004\",\"durationNanos\":2000000,\"success\":true}"),
        Arguments.of(
            new HeliosEvent.SpanClosed(
                AT, RUN, NO_SPAN, uuid(4), Duration.ofMillis(2), false, Optional.of("why")),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"SpanClosed\",\"closedSpanId\":\"00000000-0000-0000-0000-000000000004\",\"durationNanos\":2000000,\"success\":false,\"error\":\"why\"}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"SpanClosed\",\"closedSpanId\":\"00000000-0000-0000-0000-000000000004\",\"durationNanos\":2000000,\"success\":false,\"errorCategory\":\"span_failed\"}"),
        Arguments.of(
            new HeliosEvent.SubAgentStarted(AT, RUN, NO_SPAN, "worker", uuid(6)),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"SubAgentStarted\",\"subAgentName\":\"worker\",\"parentSpanId\":\"00000000-0000-0000-0000-000000000006\"}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"SubAgentStarted\",\"parentSpanId\":\"00000000-0000-0000-0000-000000000006\"}"),
        Arguments.of(
            new HeliosEvent.SubAgentCompleted(AT, RUN, NO_SPAN, "worker", Duration.ofSeconds(1)),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"SubAgentCompleted\",\"subAgentName\":\"worker\",\"durationNanos\":1000000000}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"SubAgentCompleted\",\"durationNanos\":1000000000}"),
        Arguments.of(
            new HeliosEvent.CompactionTriggered(AT, RUN, NO_SPAN, "pre", 900, 300),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"CompactionTriggered\",\"phase\":\"pre\",\"beforeTokens\":900,\"afterTokens\":300}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"CompactionTriggered\",\"beforeTokens\":900,\"afterTokens\":300}"),
        Arguments.of(
            new HeliosEvent.OptimizerCandidateProposed(
                AT, RUN, NO_SPAN, uuid(7), Optional.of(uuid(8)), "mutation"),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"OptimizerCandidateProposed\",\"candidateId\":\"00000000-0000-0000-0000-000000000007\",\"parentCandidateId\":\"00000000-0000-0000-0000-000000000008\",\"source\":\"mutation\"}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"OptimizerCandidateProposed\",\"candidateId\":\"00000000-0000-0000-0000-000000000007\",\"parentCandidateId\":\"00000000-0000-0000-0000-000000000008\"}"),
        Arguments.of(
            new HeliosEvent.OptimizerCandidateProposed(
                AT, RUN, NO_SPAN, uuid(7), Optional.empty(), "seed"),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"OptimizerCandidateProposed\",\"candidateId\":\"00000000-0000-0000-0000-000000000007\",\"parentCandidateId\":null,\"source\":\"seed\"}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"OptimizerCandidateProposed\",\"candidateId\":\"00000000-0000-0000-0000-000000000007\",\"parentCandidateId\":null}"),
        Arguments.of(
            new HeliosEvent.OptimizerCandidateScored(
                AT, RUN, NO_SPAN, uuid(7), 0.75, new double[] {1.0, 1.0E10, -0.5}),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"OptimizerCandidateScored\",\"candidateId\":\"00000000-0000-0000-0000-000000000007\",\"aggregateScore\":0.75,\"perInstanceScores\":[1.0,1.0E10,-0.5]}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"OptimizerCandidateScored\",\"candidateId\":\"00000000-0000-0000-0000-000000000007\",\"aggregateScore\":0.75,\"perInstanceScores\":[1.0,1.0E10,-0.5]}"),
        Arguments.of(
            new HeliosEvent.OptimizerCandidateScored(
                AT, RUN, NO_SPAN, uuid(7), 0.0, new double[] {}),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"OptimizerCandidateScored\",\"candidateId\":\"00000000-0000-0000-0000-000000000007\",\"aggregateScore\":0.0,\"perInstanceScores\":[]}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"OptimizerCandidateScored\",\"candidateId\":\"00000000-0000-0000-0000-000000000007\",\"aggregateScore\":0.0,\"perInstanceScores\":[]}"),
        Arguments.of(
            new HeliosEvent.Custom(AT, RUN, NO_SPAN, "k", Map.of("b", true)),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"Custom\",\"kind\":\"k\",\"data\":{\"b\":true}}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"Custom\"}"),
        Arguments.of(
            new HeliosEvent.Custom(AT, RUN, NO_SPAN, "k", Map.of("i", 7)),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"Custom\",\"kind\":\"k\",\"data\":{\"i\":7}}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"Custom\"}"),
        Arguments.of(
            new HeliosEvent.Custom(AT, RUN, NO_SPAN, "k", Map.of("l", 8L)),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"Custom\",\"kind\":\"k\",\"data\":{\"l\":8}}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"Custom\"}"),
        Arguments.of(
            new HeliosEvent.Custom(AT, RUN, NO_SPAN, "k", Map.of("d", 2.5)),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"Custom\",\"kind\":\"k\",\"data\":{\"d\":2.5}}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"Custom\"}"),
        Arguments.of(
            new HeliosEvent.Custom(AT, RUN, NO_SPAN, "k", Map.of("n", Double.NaN)),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"Custom\",\"kind\":\"k\",\"data\":{\"n\":\"NaN\"}}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"Custom\"}"),
        Arguments.of(
            new HeliosEvent.Custom(AT, RUN, NO_SPAN, "k", Map.of("f", Float.POSITIVE_INFINITY)),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"Custom\",\"kind\":\"k\",\"data\":{\"f\":\"Infinity\"}}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"Custom\"}"),
        Arguments.of(
            new HeliosEvent.Custom(AT, RUN, NO_SPAN, "k", Map.of("s", "t\"x")),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"Custom\",\"kind\":\"k\",\"data\":{\"s\":\"t\\\"x\"}}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"Custom\"}"),
        Arguments.of(
            new HeliosEvent.Custom(AT, RUN, NO_SPAN, "k", Map.of("c", new StringBuilder("sb"))),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"Custom\",\"kind\":\"k\",\"data\":{\"c\":\"sb\"}}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"Custom\"}"),
        Arguments.of(
            new HeliosEvent.Custom(AT, RUN, NO_SPAN, "k", Map.of("o", uuid(9))),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"Custom\",\"kind\":\"k\",\"data\":{\"o\":\"00000000-0000-0000-0000-000000000009\"}}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"Custom\"}"),
        Arguments.of(
            new HeliosEvent.Custom(AT, RUN, NO_SPAN, "k", Map.of()),
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"Custom\",\"kind\":\"k\",\"data\":{}}",
            "{\"at\":\"2026-05-13T10:00:00Z\",\"runId\":\"00000000-0000-0000-0000-000000000001\",\"spanId\":null,\"type\":\"Custom\"}"));
  }

  @ParameterizedTest
  @MethodSource("cases")
  void encodesEveryFieldExactly(HeliosEvent event, String full, String metadataOnly) {
    assertEquals(full, EventJsonWriter.encode(event));
  }

  @ParameterizedTest
  @MethodSource("cases")
  void encodesMetadataOnlyExactly(HeliosEvent event, String full, String metadataOnly) {
    assertEquals(metadataOnly, EventJsonWriter.encodeMetadataOnly(event));
  }

  @Test
  void nonFiniteAggregateScoreIsRejectedInBothEncodings() {
    var event =
        new HeliosEvent.OptimizerCandidateScored(
            AT, RUN, NO_SPAN, uuid(7), Double.POSITIVE_INFINITY, new double[] {});
    var full = assertThrows(IllegalArgumentException.class, () -> EventJsonWriter.encode(event));
    var meta =
        assertThrows(
            IllegalArgumentException.class, () -> EventJsonWriter.encodeMetadataOnly(event));
    assertEquals("Cannot encode non-finite number: Infinity", full.getMessage());
    assertEquals("Cannot encode non-finite number: Infinity", meta.getMessage());
  }

  @Test
  void nonFiniteInstanceScoreIsRejectedWithItsIndex() {
    var event =
        new HeliosEvent.OptimizerCandidateScored(
            AT, RUN, NO_SPAN, uuid(7), 1.0, new double[] {0.5, Double.NEGATIVE_INFINITY});
    var thrown = assertThrows(IllegalArgumentException.class, () -> EventJsonWriter.encode(event));
    assertEquals("Cannot encode non-finite number at index 1", thrown.getMessage());
  }
}
