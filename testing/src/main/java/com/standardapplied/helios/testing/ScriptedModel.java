/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.testing;

import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.schema.StructuredContentParser;
import com.standardapplied.helios.core.tool.Tool;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Flow;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * A deterministic {@link Model} that replays a fixed script of turns. Each model call consumes the
 * next scripted turn in order; when the script is exhausted the model fails fast so a test never
 * silently loops. A response turn answers {@code chat}, and {@code chatStream} through the chunk
 * sequence {@link Model}'s default synthesises from it; a stream turn ({@link
 * Builder#withStreamTurn}) answers {@code chatStream} with its publisher verbatim and fails a
 * {@code chat} call. Structured-output calls run the scripted text through the real {@link
 * StructuredContentParser} path, so schema mismatches surface exactly as they would against a live
 * provider. Thread-safe; every invocation's message history and output schema are captured for
 * assertions via {@link #calls()} and {@link #outputSchemas()}.
 */
public final class ScriptedModel implements Model {

  private record Turn(Response<Void> response, Flow.Publisher<ModelChunk> stream) {}

  private record Call(List<Message> messages, Optional<OutputSchema<?>> outputSchema) {}

  private final String id;
  private final Script script;

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private static final StructuredContentParser.JsonAdapter JSON_ADAPTER =
      new StructuredContentParser.JsonAdapter() {
        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> toMap(String json) {
          return MAPPER.readValue(json, Map.class);
        }

        @Override
        public <T> T fromMap(Map<String, Object> map, Class<T> type) {
          return MAPPER.convertValue(map, type);
        }
      };

  private ScriptedModel(String id, List<Turn> turns) {
    this.id = id;
    this.script = new Script(turns);
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  @Override
  public Response<Void> chat(List<Message> messages, List<Tool> tools) {
    return script.nextResponse(messages, null);
  }

  @Override
  public <T> Response<T> chat(
      List<Message> messages, List<Tool> tools, OutputSchema<T> outputSchema) {
    Objects.requireNonNull(outputSchema, "outputSchema must not be null");
    var scripted = script.nextResponse(messages, outputSchema);
    var builder =
        Response.newBuilder(outputSchema.type())
            .withContent(scripted.content())
            .withToolCalls(scripted.toolCalls())
            .withUsage(scripted.usage())
            .withFinishReason(scripted.finishReason())
            .withThinking(scripted.thinking())
            .withCitations(scripted.citations())
            .withMetadata(scripted.metadata());
    if (scripted.hasToolCalls() || scripted.finishReason() == FinishReason.REFUSAL) {
      return builder.build();
    }
    var parsed = StructuredContentParser.parse(scripted.content(), outputSchema, JSON_ADAPTER);
    return builder.withParsed(parsed).build();
  }

  @Override
  public Flow.Publisher<ModelChunk> chatStream(
      List<Message> messages, List<Tool> tools, CancellationToken cancellation) {
    Objects.requireNonNull(cancellation, "cancellation must not be null");
    synchronized (script) {
      return script
          .nextStream(messages, null)
          .orElseGet(() -> Model.super.chatStream(messages, tools, cancellation));
    }
  }

  @Override
  public Flow.Publisher<ModelChunk> chatStream(
      List<Message> messages,
      List<Tool> tools,
      OutputSchema<?> outputSchema,
      CancellationToken cancellation) {
    Objects.requireNonNull(outputSchema, "outputSchema must not be null");
    Objects.requireNonNull(cancellation, "cancellation must not be null");
    synchronized (script) {
      return script
          .nextStream(messages, outputSchema)
          .orElseGet(() -> Model.super.chatStream(messages, tools, outputSchema, cancellation));
    }
  }

  /**
   * Every invocation's message history, in call order. Each entry is an immutable snapshot of the
   * messages passed to that call.
   */
  public List<List<Message>> calls() {
    return script.calls().stream().map(Call::messages).toList();
  }

  /**
   * Every invocation's output schema, in call order: the schema a structured-output call carried,
   * or empty for a call without one.
   */
  public List<Optional<OutputSchema<?>>> outputSchemas() {
    return script.calls().stream().map(Call::outputSchema).toList();
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public String provider() {
    return "testing";
  }

  /** The scripted turns and the calls that have consumed them, guarded by its own monitor. */
  private static final class Script {

    private final List<Turn> turns;
    private final List<Call> calls = new ArrayList<>();

    Script(List<Turn> turns) {
      this.turns = List.copyOf(turns);
    }

    synchronized Response<Void> nextResponse(List<Message> messages, OutputSchema<?> schema) {
      var turn = next(messages, schema);
      if (turn.stream() != null) {
        throw new IllegalStateException(
            "Scripted turn %d is a stream turn: it answers chatStream, not chat"
                .formatted(calls.size()));
      }
      return turn.response();
    }

    synchronized Optional<Flow.Publisher<ModelChunk>> nextStream(
        List<Message> messages, OutputSchema<?> schema) {
      var index = calls.size();
      if (index < turns.size() && turns.get(index).stream() != null) {
        return Optional.of(next(messages, schema).stream());
      }
      return Optional.empty();
    }

    synchronized List<Call> calls() {
      return List.copyOf(calls);
    }

    private Turn next(List<Message> messages, OutputSchema<?> schema) {
      var index = calls.size();
      calls.add(new Call(List.copyOf(messages), Optional.ofNullable(schema)));
      if (index >= turns.size()) {
        throw new IllegalStateException(
            "Scripted turns exhausted: %d turn(s) scripted, call %d requested"
                .formatted(turns.size(), index + 1));
      }
      return turns.get(index);
    }
  }

  /** Builder for ScriptedModel. Turns replay in the order they are scripted. */
  public static class Builder {

    private String id = "scripted";
    private final List<Turn> turns = new ArrayList<>();

    private Builder() {}

    public Builder withId(String id) {
      this.id = Objects.requireNonNull(id, "id must not be null");
      return this;
    }

    /**
     * Scripts a text turn with zero usage. For a structured-output test, script the JSON the model
     * would emit.
     *
     * @param text the assistant text of this turn
     * @return this builder for chaining
     */
    public Builder withTextTurn(String text) {
      return withTextTurn(text, Usage.of(0, 0));
    }

    /**
     * Scripts a text turn with explicit usage.
     *
     * @param text the assistant text of this turn
     * @param usage the token usage this turn reports
     * @return this builder for chaining
     */
    public Builder withTextTurn(String text, Usage usage) {
      Objects.requireNonNull(text, "text must not be null");
      Objects.requireNonNull(usage, "usage must not be null");
      return withTurn(text, List.of(), usage, FinishReason.STOP);
    }

    /**
     * Scripts a provider-safety refusal turn ({@link FinishReason#REFUSAL}) so agent tests can
     * exercise refusal handling deterministically — e.g. asserting the session surfaces {@code
     * ResultMessage.Refusal}.
     *
     * @param text the refusal text the provider surfaced; may be empty for pre-output declines
     * @return this builder for chaining
     */
    public Builder withRefusalTurn(String text) {
      Objects.requireNonNull(text, "text must not be null");
      return withTurn(text, List.of(), Usage.of(0, 0), FinishReason.REFUSAL);
    }

    /**
     * Scripts a tool-calling turn with zero usage.
     *
     * @param toolCalls the tool calls this turn emits; at least one
     * @return this builder for chaining
     */
    public Builder withToolCallsTurn(ToolCall... toolCalls) {
      return withToolCallsTurn(Usage.of(0, 0), toolCalls);
    }

    /**
     * Scripts a tool-calling turn with explicit usage.
     *
     * @param usage the token usage this turn reports
     * @param toolCalls the tool calls this turn emits; at least one
     * @return this builder for chaining
     */
    public Builder withToolCallsTurn(Usage usage, ToolCall... toolCalls) {
      Objects.requireNonNull(usage, "usage must not be null");
      if (toolCalls == null || toolCalls.length == 0) {
        throw new IllegalArgumentException("withToolCallsTurn requires at least one tool call");
      }
      return withTurn(null, List.of(toolCalls), usage, FinishReason.TOOL_CALLS);
    }

    /**
     * Scripts a turn that answers with exactly {@code response}: any finish reason, thinking,
     * citations or provider metadata it carries reach the caller as given. A structured-output call
     * parses its content unless it carries tool calls or a {@link FinishReason#REFUSAL}.
     *
     * @param response the response this turn returns
     * @return this builder for chaining
     */
    public Builder withResponseTurn(Response<Void> response) {
      Objects.requireNonNull(response, "response must not be null");
      turns.add(new Turn(response, null));
      return this;
    }

    /**
     * Scripts a turn that answers {@code chatStream}, with or without an output schema, with {@code
     * stream} verbatim, for a chunk sequence no response synthesises: a mid-stream error, a stream
     * that never ends, chunks out of the usual order. A {@code chat} call that reaches this turn
     * fails. {@link ModelStreams} builds the common streams.
     *
     * @param stream the publisher this turn returns
     * @return this builder for chaining
     */
    public Builder withStreamTurn(Flow.Publisher<ModelChunk> stream) {
      Objects.requireNonNull(stream, "stream must not be null");
      turns.add(new Turn(null, stream));
      return this;
    }

    public ScriptedModel build() {
      return new ScriptedModel(id, turns);
    }

    private Builder withTurn(
        String text, List<ToolCall> toolCalls, Usage usage, FinishReason finishReason) {
      return withResponseTurn(
          Response.newBuilder()
              .withContent(text)
              .withToolCalls(toolCalls)
              .withUsage(usage)
              .withFinishReason(finishReason)
              .build());
    }
  }
}
