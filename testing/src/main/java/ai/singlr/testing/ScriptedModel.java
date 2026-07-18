/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.testing;

import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.Response.Usage;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.schema.StructuredContentParser;
import ai.singlr.core.tool.Tool;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * A deterministic {@link Model} that replays a fixed script of turns. Each {@code chat} call
 * consumes the next scripted turn in order; when the script is exhausted the model fails fast so a
 * test never silently loops. Structured-output calls run the scripted text through the real {@link
 * StructuredContentParser} path, so schema mismatches surface exactly as they would against a live
 * provider. Thread-safe; every invocation's message history is captured for assertions via {@link
 * #calls()}.
 */
public final class ScriptedModel implements Model {

  private record Turn(
      String text, List<ToolCall> toolCalls, Usage usage, FinishReason finishReason) {}

  private final String id;
  private final List<Turn> turns;
  private final AtomicInteger cursor = new AtomicInteger();
  private final List<List<Message>> calls = new CopyOnWriteArrayList<>();

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
    this.turns = List.copyOf(turns);
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  @Override
  public Response<Void> chat(List<Message> messages, List<Tool> tools) {
    var turn = nextTurn(messages);
    return Response.newBuilder()
        .withContent(turn.text())
        .withToolCalls(turn.toolCalls())
        .withFinishReason(turn.finishReason())
        .withUsage(turn.usage())
        .build();
  }

  @Override
  public <T> Response<T> chat(
      List<Message> messages, List<Tool> tools, OutputSchema<T> outputSchema) {
    Objects.requireNonNull(outputSchema, "outputSchema must not be null");
    var turn = nextTurn(messages);
    var builder =
        Response.newBuilder(outputSchema.type())
            .withContent(turn.text())
            .withToolCalls(turn.toolCalls())
            .withUsage(turn.usage())
            .withFinishReason(turn.finishReason());
    if (!turn.toolCalls().isEmpty() || turn.finishReason() == FinishReason.REFUSAL) {
      return builder.build();
    }
    var parsed = StructuredContentParser.parse(turn.text(), outputSchema, JSON_ADAPTER);
    return builder.withParsed(parsed).build();
  }

  /**
   * Every invocation's message history, in call order. Each entry is an immutable snapshot of the
   * messages passed to that {@code chat} call.
   */
  public List<List<Message>> calls() {
    return List.copyOf(calls);
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public String provider() {
    return "testing";
  }

  private Turn nextTurn(List<Message> messages) {
    calls.add(List.copyOf(messages));
    var index = cursor.getAndIncrement();
    if (index >= turns.size()) {
      throw new IllegalStateException(
          "Scripted turns exhausted: %d turn(s) scripted, call %d requested"
              .formatted(turns.size(), index + 1));
    }
    return turns.get(index);
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
    public Builder thenText(String text) {
      return thenText(text, Usage.of(0, 0));
    }

    /**
     * Scripts a text turn with explicit usage.
     *
     * @param text the assistant text of this turn
     * @param usage the token usage this turn reports
     * @return this builder for chaining
     */
    public Builder thenText(String text, Usage usage) {
      Objects.requireNonNull(text, "text must not be null");
      Objects.requireNonNull(usage, "usage must not be null");
      turns.add(new Turn(text, List.of(), usage, FinishReason.STOP));
      return this;
    }

    /**
     * Scripts a provider-safety refusal turn ({@link FinishReason#REFUSAL}) so agent tests can
     * exercise refusal handling deterministically — e.g. asserting the session surfaces {@code
     * ResultMessage.Refusal}.
     *
     * @param text the refusal text the provider surfaced; may be empty for pre-output declines
     * @return this builder for chaining
     */
    public Builder thenRefusal(String text) {
      Objects.requireNonNull(text, "text must not be null");
      turns.add(new Turn(text, List.of(), Usage.of(0, 0), FinishReason.REFUSAL));
      return this;
    }

    /**
     * Scripts a tool-calling turn with zero usage.
     *
     * @param toolCalls the tool calls this turn emits; at least one
     * @return this builder for chaining
     */
    public Builder thenToolCalls(ToolCall... toolCalls) {
      return thenToolCalls(Usage.of(0, 0), toolCalls);
    }

    /**
     * Scripts a tool-calling turn with explicit usage.
     *
     * @param usage the token usage this turn reports
     * @param toolCalls the tool calls this turn emits; at least one
     * @return this builder for chaining
     */
    public Builder thenToolCalls(Usage usage, ToolCall... toolCalls) {
      Objects.requireNonNull(usage, "usage must not be null");
      if (toolCalls == null || toolCalls.length == 0) {
        throw new IllegalArgumentException("thenToolCalls requires at least one tool call");
      }
      turns.add(new Turn(null, List.of(toolCalls), usage, FinishReason.TOOL_CALLS));
      return this;
    }

    public ScriptedModel build() {
      return new ScriptedModel(id, turns);
    }
  }
}
