/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static io.opentelemetry.api.common.AttributeKey.longKey;
import static io.opentelemetry.api.common.AttributeKey.stringArrayKey;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.common.JsonSnapshot;
import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.tool.ToolOutcome;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.loop.SessionState;
import com.standardapplied.helios.session.loop.SessionTotals;
import com.standardapplied.helios.session.loop.StepSpans;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The OpenTelemetry spans of a session's runs, and the one owner of their names, attributes and
 * mapping onto the GenAI semantic conventions (status Development). A run is an {@code
 * invoke_agent} span from {@link #start} to {@link #end}, a child of the context its {@code send}
 * was called in. Under it, set as their parent: a {@code chat} span per model attempt, from {@link
 * #modelAttempt} to its {@link QueryEvent.ModelCallEnded} (a compactor's call, which starts none,
 * spans the event's duration); an {@code execute_tool} span per tool call, from its {@link
 * QueryEvent.ToolUse}, or its first {@link QueryEvent.ToolResult} when it had none, to that first
 * result; and a zero-length {@code hook} span per {@link QueryEvent.HookFired}. A span still open
 * when its run ends ends then, with the run's error. Content is added only under {@link
 * TraceDetail#VERBOSE}, each string redacted against the session's secrets, the tool and hook texts
 * already by their events. It reads every event the loop emits before the session's publisher does.
 * A {@link RuntimeException} the application's OpenTelemetry SDK throws from a span call, such as a
 * span processor's, is logged and changes no run: a run whose span did not start runs untraced in
 * its parent's context, and a span that did not start or end is not traced; an {@link Error}
 * propagates. Thread-safe: each run's spans are guarded by a lock of their own.
 */
final class RunSpans implements StepSpans {

  /** The instrumentation scope of every span a session emits. */
  private static final String SCOPE = "com.standardapplied.helios";

  private static final Logger LOGGER = Logger.getLogger(AgentSessionImpl.class.getName());

  private static final AttributeKey<String> OPERATION = stringKey("gen_ai.operation.name");
  private static final AttributeKey<String> AGENT_NAME = stringKey("gen_ai.agent.name");
  private static final AttributeKey<String> CONVERSATION_ID = stringKey("gen_ai.conversation.id");
  private static final AttributeKey<String> PROVIDER = stringKey("gen_ai.provider.name");
  private static final AttributeKey<String> REQUEST_MODEL = stringKey("gen_ai.request.model");
  private static final AttributeKey<List<String>> FINISH_REASONS =
      stringArrayKey("gen_ai.response.finish_reasons");
  private static final AttributeKey<Long> INPUT_TOKENS = longKey("gen_ai.usage.input_tokens");
  private static final AttributeKey<Long> OUTPUT_TOKENS = longKey("gen_ai.usage.output_tokens");
  private static final AttributeKey<Long> CACHE_READ =
      longKey("gen_ai.usage.cache_read.input_tokens");
  private static final AttributeKey<Long> CACHE_WRITE =
      longKey("gen_ai.usage.cache_write.input_tokens");
  private static final AttributeKey<String> TOOL_NAME = stringKey("gen_ai.tool.name");
  private static final AttributeKey<String> TOOL_CALL_ID = stringKey("gen_ai.tool.call.id");
  private static final AttributeKey<String> TOOL_TYPE = stringKey("gen_ai.tool.type");
  private static final AttributeKey<String> TOOL_ARGUMENTS =
      stringKey("gen_ai.tool.call.arguments");
  private static final AttributeKey<String> TOOL_RESULT = stringKey("gen_ai.tool.call.result");
  private static final AttributeKey<String> INPUT_MESSAGES = stringKey("gen_ai.input.messages");
  private static final AttributeKey<String> OUTPUT_MESSAGES = stringKey("gen_ai.output.messages");
  private static final AttributeKey<String> ERROR_TYPE = stringKey("error.type");
  private static final AttributeKey<String> RUN_ID = stringKey("helios.run.id");
  private static final AttributeKey<String> TERMINAL = stringKey("helios.terminal");
  private static final AttributeKey<Long> ATTEMPT = longKey("helios.attempt");
  private static final AttributeKey<Long> COST = longKey("helios.cost.micro_usd");
  private static final AttributeKey<String> TOOL_OUTCOME = stringKey("helios.tool.outcome");
  private static final AttributeKey<Long> ARGUMENTS_CHARS = longKey("helios.tool.argumentsChars");
  private static final AttributeKey<Long> RESULT_CHARS = longKey("helios.tool.resultChars");
  private static final AttributeKey<String> HOOK_NAME = stringKey("helios.hook.name");
  private static final AttributeKey<String> HOOK_PHASE = stringKey("helios.hook.phase");
  private static final AttributeKey<String> HOOK_OUTCOME = stringKey("helios.hook.outcome");
  private static final AttributeKey<String> HOOK_TEXT = stringKey("helios.hook.text");

  private final Tracer tracer;
  private final String sessionId;
  private final String agentName;
  private final String modelId;
  private final String provider;
  private final boolean verbose;
  private final SecretRegistry secrets;
  private final Map<String, RunSpan> runs = new ConcurrentHashMap<>();
  private final ContextKey<Context> runParent = ContextKey.named("helios.run.parent");

  /**
   * @throws NullPointerException if the options' model reports no id or provider
   */
  RunSpans(SessionOptions options) {
    this.tracer = options.openTelemetry().orElseGet(GlobalOpenTelemetry::get).getTracer(SCOPE);
    this.sessionId = options.sessionId();
    this.agentName = options.agentName();
    this.modelId = Objects.requireNonNull(options.model().id(), "model id must not be null");
    this.provider =
        providerName(
            Objects.requireNonNull(options.model().provider(), "model provider must not be null"));
    this.verbose = options.traceDetail() == TraceDetail.VERBOSE;
    this.secrets = options.secretRegistry();
  }

  /**
   * Start {@code run}'s span as a child of {@code current}, or, when {@code current} is a context
   * of one of this session's runs, of that run's parent, so a run never becomes the parent of a
   * later one.
   *
   * @return the context the run runs in: its parent with the run's span, or its parent alone when
   *     the span did not start
   */
  Context start(SessionState run, Context current) {
    var parent = Objects.requireNonNullElse(current.get(runParent), current);
    return traced(run.runId(), () -> started(run, parent), parent);
  }

  private Context started(SessionState run, Context parent) {
    var span =
        tracer
            .spanBuilder("invoke_agent " + agentName)
            .setParent(parent)
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(OPERATION, "invoke_agent")
            .setAttribute(AGENT_NAME, agentName)
            .setAttribute(CONVERSATION_ID, sessionId)
            .setAttribute(RUN_ID, run.runId())
            .setAttribute(PROVIDER, provider)
            .setAttribute(REQUEST_MODEL, modelId)
            .startSpan();
    var context = parent.with(span).with(runParent, parent);
    runs.put(run.runId(), new RunSpan(run.runId(), span, context));
    return context;
  }

  /** Record {@code event} in the spans of its run; an event of no run in progress is ignored. */
  void accept(QueryEvent event) {
    var run = runs.get(event.runId());
    if (run != null) {
      traced(event.runId(), () -> run.accept(event));
    }
  }

  /** The context the attempt runs in: the current one, untraced, when the run has no span. */
  @Override
  public Context modelAttempt(SessionState state) {
    var run = runs.get(state.runId());
    return run == null ? Context.current() : traced(state.runId(), run::modelAttempt, run.context);
  }

  /** The context the call runs in: the current one, untraced, when the run has no span. */
  @Override
  public Context toolCall(SessionState state, ToolCall call) {
    var run = runs.get(state.runId());
    return run == null ? Context.current() : run.toolCall(call);
  }

  /**
   * End {@code run}'s span and every span of it still open; from now on its events are ignored. A
   * run whose {@link #start} failed has no span to end.
   *
   * @param run the run's state, whose totals are its span's
   * @param terminal the run's terminal, or null when it ended without one
   * @param crash the {@link RuntimeException} that left the run without a terminal, or null
   */
  void end(SessionState run, ResultMessage terminal, RuntimeException crash) {
    var spans = runs.remove(run.runId());
    if (spans == null) {
      return;
    }
    var failure = Failure.of(run.ending().first(), crash, terminal, secrets);
    var output = terminal instanceof ResultMessage.Success success ? success.result() : null;
    traced(run.runId(), () -> spans.end(run.totals(), terminal, failure, output));
  }

  private void traced(String runId, Runnable call) {
    traced(
        runId,
        () -> {
          call.run();
          return Context.root();
        },
        Context.root());
  }

  /**
   * What {@code call} returns, or {@code fallback} once the {@link RuntimeException} it threw is
   * logged, redacted, or only its class name when it cannot be read.
   */
  private Context traced(String runId, Supplier<Context> call, Context fallback) {
    try {
      return call.get();
    } catch (RuntimeException e) {
      LOGGER.log(
          Level.WARNING,
          "a span of run "
              + runId
              + " of session "
              + sessionId
              + " failed; the run's result is unchanged",
          forLog(e));
      return fallback;
    }
  }

  private Throwable forLog(RuntimeException failure) {
    try {
      return SerializedError.redactedForLog(failure, secrets.redactor());
    } catch (RuntimeException unreadable) {
      return new Throwable(failure.getClass().getName());
    }
  }

  private String redacted(String text) {
    return secrets.redactor().redact(text).text();
  }

  /** The provider's {@code gen_ai.provider.name}: {@code gemini} is {@code gcp.gemini}. */
  private static String providerName(String provider) {
    return "gemini".equals(provider) ? "gcp.gemini" : provider;
  }

  /**
   * Set the usage attributes when {@code usage} reports tokens, OpenTelemetry's input count
   * including the cached tokens, and the cost when there is one.
   */
  private static void usage(Span span, Usage usage, CostEstimate cost) {
    if (usage.reportsTokens()) {
      span.setAttribute(
          INPUT_TOKENS,
          (long) usage.inputTokens()
              + usage.cacheCreationInputTokens()
              + usage.cacheReadInputTokens());
      span.setAttribute(OUTPUT_TOKENS, (long) usage.outputTokens());
      if (usage.cacheReadInputTokens() != 0) {
        span.setAttribute(CACHE_READ, (long) usage.cacheReadInputTokens());
      }
      if (usage.cacheCreationInputTokens() != 0) {
        span.setAttribute(CACHE_WRITE, (long) usage.cacheCreationInputTokens());
      }
    }
    if (cost.microUsd() != 0) {
      span.setAttribute(COST, cost.microUsd());
    }
  }

  private static void fail(Span span, String description, String type) {
    span.setStatus(StatusCode.ERROR, description).setAttribute(ERROR_TYPE, type);
  }

  /** A one-message {@code gen_ai.*.messages} value holding {@code text} as its one text part. */
  private static String message(String role, String text) {
    var part = new LinkedHashMap<String, Object>();
    part.put("type", "text");
    part.put("content", text);
    var message = new LinkedHashMap<String, Object>();
    message.put("role", role);
    message.put("parts", List.of(part));
    return "[" + JsonSnapshot.write(message) + "]";
  }

  /** The spans of one run in progress: its own, its open model attempt and its tool calls. */
  private final class RunSpan {

    private final String runId;
    private final Span span;
    private final Context context;
    private final Map<String, Span> tools = new HashMap<>();
    private final Set<String> endedTools = new HashSet<>();
    private Span model;
    private String input;

    RunSpan(String runId, Span span, Context context) {
      this.runId = runId;
      this.span = span;
      this.context = context;
    }

    synchronized void accept(QueryEvent event) {
      switch (event) {
        case QueryEvent.ModelCallEnded call -> modelCallEnded(call);
        case QueryEvent.ToolUse use -> tool(use.call());
        case QueryEvent.ToolResult result -> toolResult(result);
        case QueryEvent.HookFired hook -> hook(hook);
        case QueryEvent.UserMessageReceived received when input == null ->
            input = received.message().text();
        default -> {}
      }
    }

    synchronized Context modelAttempt() {
      model = chat(modelId).startSpan();
      return context.with(model);
    }

    synchronized Context toolCall(ToolCall call) {
      var tool = tools.get(call.id());
      return tool == null ? context : context.with(tool);
    }

    synchronized void end(
        SessionTotals totals, ResultMessage terminal, Failure failure, String output) {
      try {
        if (model != null) {
          failure.end(model);
        }
        tools.forEach(
            (callId, tool) -> {
              if (!endedTools.contains(callId)) {
                failure.end(tool);
              }
            });
      } finally {
        usage(span, totals.usage(), totals.cost());
        if (terminal != null) {
          span.setAttribute(TERMINAL, terminal.getClass().getSimpleName());
        }
        if (verbose && input != null) {
          span.setAttribute(INPUT_MESSAGES, message("user", redacted(input)));
        }
        if (verbose && output != null) {
          span.setAttribute(OUTPUT_MESSAGES, message("assistant", redacted(output)));
        }
        failure.end(span);
      }
    }

    private SpanBuilder child(String name, SpanKind kind) {
      return tracer
          .spanBuilder(name)
          .setParent(context)
          .setSpanKind(kind)
          .setAttribute(RUN_ID, runId);
    }

    private SpanBuilder chat(String model) {
      return child("chat " + model, SpanKind.CLIENT)
          .setAttribute(OPERATION, "chat")
          .setAttribute(PROVIDER, provider)
          .setAttribute(REQUEST_MODEL, model);
    }

    /** End the open attempt, or span a call no attempt was started for over its duration. */
    private void modelCallEnded(QueryEvent.ModelCallEnded call) {
      if (model != null) {
        describe(model, call).end();
      } else {
        var started = call.timestamp().minus(call.duration());
        describe(chat(call.modelId()).setStartTimestamp(started).startSpan(), call)
            .end(call.timestamp());
      }
      model = null;
    }

    private Span describe(Span attempt, QueryEvent.ModelCallEnded call) {
      attempt
          .setAttribute(
              FINISH_REASONS, List.of(call.finishReason().name().toLowerCase(Locale.ROOT)))
          .setAttribute(ATTEMPT, (long) call.attempt());
      usage(attempt, call.usage(), call.cost());
      var error = call.error();
      if (error != null) {
        fail(attempt, error.kind() + ": " + error.message(), error.kind());
      }
      return attempt;
    }

    private Span tool(ToolCall call) {
      return tools.computeIfAbsent(
          call.id(),
          callId -> {
            var arguments = JsonSnapshot.write(call.arguments());
            var tool =
                child("execute_tool " + call.name(), SpanKind.INTERNAL)
                    .setAttribute(OPERATION, "execute_tool")
                    .setAttribute(TOOL_NAME, call.name())
                    .setAttribute(TOOL_CALL_ID, callId)
                    .setAttribute(TOOL_TYPE, "function")
                    .setAttribute(ARGUMENTS_CHARS, (long) arguments.length());
            if (verbose) {
              tool.setAttribute(TOOL_ARGUMENTS, arguments);
            }
            return tool.startSpan();
          });
    }

    /**
     * End the call's span at its first result, recording what the tool returned; a later result, a
     * {@code PostToolUse} rewrite, reaches no ended span.
     */
    private void toolResult(QueryEvent.ToolResult event) {
      var tool = tool(event.call());
      if (!endedTools.add(event.call().id())) {
        return;
      }
      var output = event.result().output();
      tool.setAttribute(RESULT_CHARS, (long) output.length());
      if (verbose) {
        tool.setAttribute(TOOL_RESULT, output);
      }
      var outcome = event.result().outcome();
      var name = outcome.getClass().getSimpleName();
      tool.setAttribute(TOOL_OUTCOME, name);
      if (!(outcome instanceof ToolOutcome.Succeeded)) {
        fail(tool, name, name);
      }
      tool.end();
    }

    private void hook(QueryEvent.HookFired hook) {
      var builder =
          child("hook " + hook.hookName(), SpanKind.INTERNAL)
              .setStartTimestamp(hook.timestamp())
              .setAttribute(HOOK_NAME, hook.hookName())
              .setAttribute(HOOK_PHASE, hook.phase().name())
              .setAttribute(HOOK_OUTCOME, hook.outcome().getClass().getSimpleName());
      if (verbose) {
        text(hook.outcome()).ifPresent(text -> builder.setAttribute(HOOK_TEXT, text));
      }
      builder.startSpan().end(hook.timestamp());
    }

    /** The reason or text an outcome carries, already redacted by its event. */
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
  }

  /**
   * A run's error, null for a {@code Success}, and its {@code error.type}: the first host {@link
   * Error} the session recorded, else the crash, each as its redacted {@code kind: message}, else
   * the terminal's simple name, with the {@code kind: message} of the error it carries.
   */
  private record Failure(String error, String type) {

    static Failure of(
        Optional<Error> host,
        RuntimeException crash,
        ResultMessage terminal,
        SecretRegistry secrets) {
      Throwable thrown = host.isPresent() ? host.get() : crash;
      if (thrown != null) {
        return new Failure(described(thrown, secrets), thrown.getClass().getName());
      }
      var name = terminal.getClass().getSimpleName();
      return switch (terminal) {
        case ResultMessage.Success _ -> new Failure(null, null);
        case ResultMessage.ErrorDuringExecution failed ->
            new Failure(name + ": " + described(failed.error()), name);
        case ResultMessage.ErrorTransientStream failed ->
            new Failure(name + ": " + described(failed.error()), name);
        default -> new Failure(name, name);
      };
    }

    /** End {@code span}, failed with this error unless there is none. */
    void end(Span span) {
      if (error != null) {
        fail(span, error, type);
      }
      span.end();
    }

    /**
     * {@code thrown}'s redacted {@code kind: message}, or its class name when that cannot be read.
     */
    private static String described(Throwable thrown, SecretRegistry secrets) {
      try {
        return described(SerializedError.of(thrown).redactedBy(secrets.redactor()));
      } catch (RuntimeException unreadable) {
        return thrown.getClass().getName();
      }
    }

    private static String described(SerializedError error) {
      return error.kind() + ": " + error.message();
    }
  }
}
