/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.model.ToolChoice;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.tool.ParameterType;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolContext;
import com.standardapplied.helios.core.tool.ToolParameter;
import com.standardapplied.helios.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The live cases every provider's model passes against its real API: a simple chat, a system
 * message, usage, streaming, a tool call, a multi-turn conversation, its metadata, a full tool
 * round trip and structured output. A provider's integration test extends this, supplies its model
 * through {@link #model()}, builds a model from a given configuration through {@link
 * #model(ModelConfig.Builder)} and gates itself on its API key.
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

  @Test
  void simpleChat() {
    var messages = List.of(Message.user("What is 2 + 2? Reply with just the number."));

    var response = model().chat(messages);

    assertNotNull(response);
    assertNotNull(response.content());
    assertTrue(response.content().contains("4"));
    assertEquals(FinishReason.STOP, response.finishReason());
  }

  @Test
  void chatWithSystemMessage() {
    var messages =
        List.of(
            Message.system("You are a pirate. Always respond in pirate speak."),
            Message.user("Hello, how are you?"));

    var response = model().chat(messages);

    assertNotNull(response);
    assertNotNull(response.content());
    assertTrue(
        response.content().toLowerCase().contains("arr")
            || response.content().toLowerCase().contains("ahoy")
            || response.content().toLowerCase().contains("matey")
            || response.content().toLowerCase().contains("ye"));
  }

  @Test
  void chatWithUsageStats() {
    var messages = List.of(Message.user("Say hello"));

    var response = model().chat(messages);

    assertNotNull(response.usage());
    assertTrue(response.usage().inputTokens() > 0);
    assertTrue(response.usage().outputTokens() > 0);
    assertTrue(response.usage().totalTokens() > 0);
  }

  @Test
  void streamingChat() {
    var messages = List.of(Message.user("Count from 1 to 5, one number per line."));

    var iterator = model().chatStream(messages, List.of());

    var textDeltas = new ArrayList<String>();
    StreamEvent.Done doneEvent = null;

    while (iterator.hasNext()) {
      var event = iterator.next();
      if (event instanceof StreamEvent.TextDelta(String text)) {
        textDeltas.add(text);
      } else if (event instanceof StreamEvent.Done done) {
        doneEvent = done;
      }
    }

    assertFalse(textDeltas.isEmpty());
    assertNotNull(doneEvent);
    assertNotNull(doneEvent.response());

    var fullContent = String.join("", textDeltas);
    assertTrue(fullContent.contains("1"));
    assertTrue(fullContent.contains("5"));
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
      var response = forced.chat(messages, List.of(weatherTool));

      assertEquals(1, response.toolCalls().size());
      var toolCall = response.toolCalls().getFirst();
      assertEquals("get_weather", toolCall.name());
      var location = assertInstanceOf(String.class, toolCall.arguments().get("location"));
      assertFalse(location.isBlank());
    }
  }

  @Test
  void multiTurnConversation() {
    var messages = new ArrayList<Message>();
    messages.add(Message.user("My name is Alice."));

    var response1 = model().chat(messages);
    assertNotNull(response1);

    messages.add(Message.assistant(response1.content()));
    messages.add(Message.user("What is my name?"));

    var response2 = model().chat(messages);

    assertNotNull(response2);
    assertTrue(response2.content().toLowerCase().contains("alice"));
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

    var response1 = model().chat(messages, List.of(searchPeople));
    assertNotNull(response1);
    assertEquals(FinishReason.TOOL_CALLS, response1.finishReason());
    assertFalse(response1.toolCalls().isEmpty());

    var toolCall = response1.toolCalls().getFirst();
    var toolResult = searchPeople.execute(toolCall.arguments(), ToolContext.noop());

    var messages2 = new ArrayList<>(messages);
    messages2.add(response1.toMessage());
    messages2.add(Message.tool(toolCall.id(), toolCall.name(), toolResult.output()));

    var response2 = model().chat(messages2, List.of(searchPeople));
    assertNotNull(response2);
    assertNotNull(response2.content());
    assertEquals(FinishReason.STOP, response2.finishReason());
    assertTrue(response2.content().toLowerCase().contains("alice"));
  }

  @Test
  void chatWithStructuredOutput() {
    var messages =
        List.of(
            Message.user(
                "Extract the person info: John Smith is a 35-year-old software engineer."));

    var response = model().chat(messages, OutputSchema.of(Person.class));

    assertNotNull(response);
    assertNotNull(response.content());
    assertTrue(response.hasParsed(), "Expected parsed output to be present");

    var person = response.parsed();
    assertNotNull(person);
    assertEquals("John Smith", person.name());
    assertEquals(35, person.age());
    assertTrue(
        person.occupation().toLowerCase().contains("software")
            || person.occupation().toLowerCase().contains("engineer"));
  }
}
