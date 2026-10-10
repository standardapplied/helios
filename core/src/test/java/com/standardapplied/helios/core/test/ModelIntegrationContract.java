/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.ToolChoice;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.tool.ParameterType;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolContext;
import com.standardapplied.helios.core.tool.ToolParameter;
import com.standardapplied.helios.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * The live cases every provider's model passes against its real API: a simple chat, a system
 * message, usage, streaming, a tool call, a multi-turn conversation, its metadata, a full tool
 * round trip and structured output. Each asserts only what holds for any reply the model may write:
 * the provider accepted the request and Helios read the reply into its own types; tool use is
 * forced. A provider's integration test extends this, supplies its model through {@link #model()},
 * builds a model from a given configuration through {@link #model(ModelConfig.Builder)} and gates
 * itself on its API key.
 */
public abstract class ModelIntegrationContract {

  /** The structured answer {@code chatWithStructuredOutput} asks for. */
  public record Person(String name, int age, String occupation) {}

  private final String expectedId;
  private final String expectedProvider;
  private final int expectedContextWindow;

  /**
   * Creates the contract for a model that reports the given metadata.
   *
   * @param expectedId the id the model reports
   * @param expectedProvider the provider name the model reports
   * @param expectedContextWindow the context window the model reports
   */
  protected ModelIntegrationContract(
      String expectedId, String expectedProvider, int expectedContextWindow) {
    this.expectedId = expectedId;
    this.expectedProvider = expectedProvider;
    this.expectedContextWindow = expectedContextWindow;
  }

  /**
   * The model every case talks to, built once for the test class.
   *
   * @return the model under test
   */
  protected abstract Model model();

  /**
   * A model of the same id as {@link #model()}, built from {@code config} completed with the
   * provider's API key. The caller closes it.
   *
   * @param config the configuration the case needs, without the API key
   * @return a new model the caller owns
   */
  protected abstract Model model(ModelConfig.Builder config);

  /**
   * The recorded test that proves this provider's structured-output parsing, for a provider that
   * does not constrain the model's output to the schema. Empty, the default, when the provider does
   * constrain it, so a reply that does not parse fails the case instead of skipping it.
   *
   * @return the recorded test, or empty when the provider constrains structured output
   */
  protected Optional<String> unconstrainedStructuredOutputCounterpart() {
    return Optional.empty();
  }

  @Test
  void simpleChat() {
    Accepted.textReply(
        model().chat(List.of(Message.user("What is 2 + 2? Reply with just the number."))));
  }

  @Test
  void chatWithSystemMessage() {
    var messages =
        List.of(
            Message.system("You are a pirate. Always respond in pirate speak."),
            Message.user("Hello, how are you?"));

    Accepted.textReply(model().chat(messages));
  }

  @Test
  void chatWithUsageStats() {
    var response = model().chat(List.of(Message.user("Say hello")));

    assertNotNull(response.usage());
    assertTrue(response.usage().inputTokens() > 0);
    assertTrue(response.usage().outputTokens() > 0);
    assertTrue(response.usage().totalTokens() > 0);
  }

  @Test
  void streamingChat() {
    var messages = List.of(Message.user("Count from 1 to 5, one number per line."));

    Accepted.textReply(Accepted.stream(model().chatStream(messages, List.of())));
  }

  @Test
  void chatWithToolCall() {
    var weatherTool =
        Tool.newBuilder()
            .withName("get_weather")
            .withDescription("Get the current weather for a location")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("location")
                    .withType(ParameterType.STRING)
                    .withDescription("The city name")
                    .withRequired(true)
                    .build())
            .withExecutor(
                (args, ctx) -> {
                  var location = (String) args.get("location");
                  return ToolResult.success("Weather in " + location + ": 72°F, sunny");
                })
            .build();

    var messages = List.of(Message.user("What's the weather in San Francisco?"));

    try (var forced =
        model(ModelConfig.newBuilder().withToolChoice(ToolChoice.required("get_weather")))) {
      var response = Accepted.toolTurn(forced.chat(messages, List.of(weatherTool)));

      assertEquals(FinishReason.TOOL_CALLS, response.finishReason());
      for (var toolCall : response.toolCalls()) {
        assertEquals("get_weather", toolCall.name());
        assertNotNull(toolCall.arguments());
      }
    }
  }

  @Test
  void multiTurnConversation() {
    var messages = new ArrayList<Message>();
    messages.add(Message.user("My name is Alice."));
    var response1 = Accepted.textReply(model().chat(messages));

    messages.add(Message.assistant(response1.content()));
    messages.add(Message.user("What is my name?"));

    Accepted.textReply(model().chat(messages));
  }

  @Test
  void modelMetadata() {
    assertEquals(expectedId, model().id());
    assertEquals(expectedProvider, model().provider());
    assertEquals(expectedContextWindow, model().contextWindow());
  }

  @Test
  void fullToolRoundTrip() {
    var searchPeople =
        Tool.newBuilder()
            .withName("search_people")
            .withDescription("Finds people using semantic search")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("query")
                    .withDescription("Natural language description of who to find")
                    .withType(ParameterType.STRING)
                    .withRequired(true)
                    .build())
            .withExecutor(
                (args, ctx) ->
                    ToolResult.success("[{\"name\":\"Alice\",\"headline\":\"AI researcher\"}]"))
            .build();

    var messages =
        List.of(
            Message.system(
                "You are a helpful assistant. Use the search_people tool when asked to find people."),
            Message.user("Find me AI researchers"));

    try (var forced =
            model(ModelConfig.newBuilder().withToolChoice(ToolChoice.required("search_people")));
        var toolless = model(ModelConfig.newBuilder().withToolChoice(ToolChoice.none()))) {
      var response1 = Accepted.toolTurn(forced.chat(messages, List.of(searchPeople)));
      assertEquals(FinishReason.TOOL_CALLS, response1.finishReason());

      var messages2 = new ArrayList<>(messages);
      messages2.add(response1.toMessage());
      for (var toolCall : response1.toolCalls()) {
        assertEquals("search_people", toolCall.name());
        var toolResult = searchPeople.execute(toolCall.arguments(), ToolContext.noop());
        messages2.add(Message.tool(toolCall.id(), toolCall.name(), toolResult.output()));
      }

      Accepted.textReply(toolless.chat(messages2, List.of(searchPeople)));
    }
  }

  @Test
  void chatWithStructuredOutput() {
    var messages =
        List.of(
            Message.user(
                "Extract the person info: John Smith is a 35-year-old software engineer."));

    Supplier<Response<Person>> call = () -> model().chat(messages, OutputSchema.of(Person.class));
    var response =
        unconstrainedStructuredOutputCounterpart()
            .map(counterpart -> Accepted.parsedOrSkip(expectedId, counterpart, call))
            .orElseGet(call);

    assertTrue(response.hasParsed(), "Expected parsed output to be present");
    assertNotNull(response.parsed());
  }
}
