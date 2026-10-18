/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * What every provider's request carries of a conversation, proven offline against a stub: the
 * system message, every earlier turn in order, and a decoded tool call's arguments exactly as they
 * streamed, each JSON {@code null} included. These are the request-shape guarantees a live test
 * cannot check without reading what the model wrote. A provider's test extends this, supplies its
 * model at a stub's address, a recorded text reply and a recorded tool call, parses the request
 * body Helios sent, and reads the system prompt, the turn texts and the replayed arguments out of
 * it.
 */
public abstract class ConversationRequestContract {

  private static final String SYSTEM = "You are a pirate. Always respond in pirate speak.";

  /**
   * The provider's model, built to send its requests to the stub at the given address.
   *
   * @return a function from the stub's address to a new model the harness closes
   */
  protected abstract Function<URI, Model> modelAt();

  /**
   * The golden file holding a recorded text reply the stub answers every request with.
   *
   * @return the golden file name
   */
  protected abstract String reply();

  /**
   * The golden file holding a recorded reply that calls tool {@code weather} with {@code
   * {"city":"Paris","unit":null,"days":[1,null],"options":{"lang":null}}}.
   *
   * @return the golden file name
   */
  protected abstract String toolCallReply();

  /**
   * The arguments of the one tool call the request replays.
   *
   * @param request the parsed request body
   * @return the arguments, parsed
   */
  protected abstract Map<String, Object> replayedArguments(Map<String, Object> request);

  /**
   * The request body parsed as a JSON object.
   *
   * @param body the request body Helios sent
   * @return its fields
   */
  protected abstract Map<String, Object> parse(String body);

  /**
   * The system prompt the request carries.
   *
   * @param request the parsed request body
   * @return the system prompt, or {@code null} when the request carries none
   */
  protected abstract String systemPrompt(Map<String, Object> request);

  /**
   * The text of every conversation turn the request carries, in order, without the system prompt.
   *
   * @param request the parsed request body
   * @return the turn texts
   */
  protected abstract List<String> turnTexts(Map<String, Object> request);

  @Test
  void theSystemMessageIsSent() {
    var request = send(List.of(Message.system(SYSTEM), Message.user("Hello, how are you?")));

    assertEquals(SYSTEM, systemPrompt(request));
    assertEquals(List.of("Hello, how are you?"), turnTexts(request));
  }

  @Test
  void theEarlierTurnsAreCarried() {
    var turns = List.of("My name is Alice.", "Nice to meet you, Alice.", "What is my name?");

    var request =
        send(
            List.of(
                Message.user(turns.get(0)),
                Message.assistant(turns.get(1)),
                Message.user(turns.get(2))));

    assertEquals(turns, turnTexts(request));
  }

  @Test
  void aDecodedToolCallIsSentBackWithEveryNullArgument() {
    var arguments = new LinkedHashMap<String, Object>();
    arguments.put("city", "Paris");
    arguments.put("unit", null);
    arguments.put("days", Arrays.asList(1, null));
    arguments.put("options", Collections.singletonMap("lang", null));
    var ask = Message.user("Weather in Paris?");
    var replies = List.of(Golden.read(toolCallReply()), Golden.read(reply()));

    var requests =
        ModelHarness.exchange(
            replies,
            modelAt(),
            model -> {
              var call =
                  model.chat(List.of(ask), ConversationFixture.tools()).toolCalls().getFirst();
              assertEquals(arguments, call.arguments());
              model.chat(
                  List.of(
                      ask,
                      Message.assistant(List.of(call)),
                      Message.tool(call.id(), call.name(), "18C and sunny")),
                  ConversationFixture.tools());
            });

    assertEquals(2, requests.size());
    assertEquals(arguments, replayedArguments(parse(requests.getLast().body())));
  }

  /** The parsed body of the one request the model sends for {@code messages}. */
  private Map<String, Object> send(List<Message> messages) {
    var requests =
        ModelHarness.exchange(
            List.of(Golden.read(reply())), modelAt(), model -> model.chat(messages, List.of()));
    assertEquals(1, requests.size());
    return parse(requests.getFirst().body());
  }

  /**
   * The texts a content field holds: the field itself when it is a string, else the {@code text} of
   * each of its blocks.
   *
   * @param content a parsed content field
   * @return its texts
   */
  protected static Stream<String> texts(Object content) {
    return content instanceof String text
        ? Stream.of(text)
        : objects(content).stream().map(block -> (String) block.get("text"));
  }

  /**
   * {@code value}, a JSON array of objects, or an empty list when the field is absent.
   *
   * @param value a parsed JSON value
   * @return its objects
   */
  @SuppressWarnings("unchecked")
  protected static List<Map<String, Object>> objects(Object value) {
    return value == null ? List.of() : (List<Map<String, Object>>) value;
  }
}
