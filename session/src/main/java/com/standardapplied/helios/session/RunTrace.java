/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import com.standardapplied.helios.core.common.JsonSnapshot;
import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.tool.ToolOutcome;
import com.standardapplied.helios.core.trace.Span;
import com.standardapplied.helios.core.trace.SpanKind;
import com.standardapplied.helios.core.trace.Trace;
import com.standardapplied.helios.session.hooks.HookOutcome;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The trace of one run in progress, built from its events: a {@code model.call} span per {@link
 * QueryEvent.ModelCallEnded}, a {@code tool.<name>} span per tool call, from its {@link
 * QueryEvent.ToolUse}, or its first {@link QueryEvent.ToolResult} when it had none, to that first
 * result, and a zero-length {@code hook.<name>} span per {@link QueryEvent.HookFired}, naming its
 * phase and outcome. Content (the run's first user message, the tool arguments and results, a
 * hook's reason or text) is kept only when {@code verbose}, each string redacted against the
 * session's secrets. Not thread-safe: {@link SessionTraceRecorder} holds its lock around every
 * call.
 */
final class RunTrace {

  private final Instant start;
  private final boolean verbose;
  private final SecretRegistry secrets;
  private final List<Span.Builder> spans = new ArrayList<>();
  private final Map<String, Span.Builder> tools = new HashMap<>();
  private final Set<String> endedTools = new HashSet<>();
  private String inputText;

  RunTrace(Instant start, boolean verbose, SecretRegistry secrets) {
    this.start = start;
    this.verbose = verbose;
    this.secrets = secrets;
  }

  void accept(QueryEvent event) {
    switch (event) {
      case QueryEvent.ModelCallEnded call -> modelCall(call);
      case QueryEvent.ToolUse use -> toolSpan(use.call(), use.timestamp());
      case QueryEvent.ToolResult result -> toolResult(result);
      case QueryEvent.HookFired hook -> hook(hook);
      case QueryEvent.UserMessageReceived received when inputText == null ->
          inputText = received.message().text();
      default -> {}
    }
  }

  /**
   * The finished trace: spans still open end at {@code end} and fail with {@code ending}'s error;
   * the trace's own fields are the caller's.
   */
  Trace.Builder finish(Instant end, TraceEnding ending) {
    tools.forEach(
        (callId, span) -> {
          if (!endedTools.contains(callId)) {
            span.withEndTime(utc(end)).withError(ending.error());
          }
        });
    var trace =
        Trace.newBuilder()
            .withStartTime(utc(start))
            .withEndTime(utc(end))
            .withSpans(spans.stream().map(Span.Builder::build).toList());
    if (verbose) {
      trace.withInputText(redacted(inputText)).withOutputText(redacted(ending.output()));
    }
    return trace;
  }

  private void modelCall(QueryEvent.ModelCallEnded call) {
    var span =
        span("model.call", SpanKind.MODEL_CALL, call.timestamp().minus(call.duration()))
            .withEndTime(utc(call.timestamp()))
            .withDuration(call.duration())
            .withAttribute("model", call.modelId())
            .withAttribute("attempt", String.valueOf(call.attempt()))
            .withAttribute("finishReason", call.finishReason().name())
            .withUsage(call.usage())
            .withCost(call.cost());
    Optional.ofNullable(call.error())
        .ifPresent(
            error ->
                span.withError(error.kind() + ": " + error.message())
                    .withAttribute("error.type", error.kind()));
  }

  private Span.Builder toolSpan(ToolCall call, Instant timestamp) {
    return tools.computeIfAbsent(
        call.id(),
        callId -> {
          var arguments = JsonSnapshot.write(call.arguments());
          var span =
              span("tool." + call.name(), SpanKind.TOOL_EXECUTION, timestamp)
                  .withAttribute("tool.name", call.name())
                  .withAttribute("tool.callId", callId)
                  .withAttribute("tool.argumentsChars", String.valueOf(arguments.length()));
          if (verbose) {
            span.withAttribute("tool.arguments", redacted(arguments));
          }
          return span;
        });
  }

  /**
   * End the call's span at its first result; a later result, a {@code PostToolUse} rewrite, only
   * replaces the output it records. The span records what the tool returned, not what the model
   * read: history may hold a shorter copy.
   */
  private void toolResult(QueryEvent.ToolResult event) {
    var span = toolSpan(event.call(), event.timestamp());
    var output = event.result().output();
    span.withAttribute("tool.resultChars", String.valueOf(output.length()));
    if (verbose) {
      span.withAttribute("tool.result", redacted(output));
    }
    if (endedTools.add(event.call().id())) {
      var outcome = event.result().outcome();
      var name = outcome.getClass().getSimpleName();
      span.withEndTime(utc(event.timestamp())).withAttribute("tool.outcome", name);
      if (!(outcome instanceof ToolOutcome.Succeeded)) {
        span.withError(name).withAttribute("error.type", name);
      }
    }
  }

  private void hook(QueryEvent.HookFired hook) {
    var span =
        span("hook." + hook.hookName(), SpanKind.CUSTOM, hook.timestamp())
            .withEndTime(utc(hook.timestamp()))
            .withAttribute("hook.phase", hook.phase().name())
            .withAttribute("hook.outcome", hook.outcome().getClass().getSimpleName());
    if (verbose) {
      text(hook.outcome()).ifPresent(text -> span.withAttribute("hook.text", text));
    }
  }

  /** The reason or text an outcome carries, already redacted by the event. */
  private static Optional<String> text(HookOutcome outcome) {
    return switch (outcome) {
      case HookOutcome.Block block -> Optional.of(block.reason());
      case HookOutcome.Inject inject -> Optional.of(inject.userMessage());
      case HookOutcome.Stop stop -> Optional.of(stop.result());
      case HookOutcome.MutateText mutate -> Optional.of(mutate.text());
      case HookOutcome.MutateResult mutate -> Optional.of(mutate.output());
      case HookOutcome.Continue _, HookOutcome.MutateArgs _, HookOutcome.MutateHistory _ ->
          Optional.empty();
    };
  }

  private Span.Builder span(String name, SpanKind kind, Instant start) {
    var span = Span.newBuilder().withName(name).withKind(kind).withStartTime(utc(start));
    spans.add(span);
    return span;
  }

  private String redacted(String text) {
    return text == null ? null : secrets.redactor().redact(text).text();
  }

  static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  /**
   * How the run ended, as its spans and content see it: its error, null for a {@code Success}, and
   * a successful run's result, null otherwise.
   */
  record TraceEnding(String error, String output) {}
}
