/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * What every provider's request carries of a conversation, proven offline against a stub: the
 * system message, and every earlier turn in order. These are the request-shape guarantees a live
 * test cannot check without reading what the model wrote. A provider's test extends this, supplies
 * its model at a stub's address and a recorded text reply, parses the request body Helios sent, and
 * reads the system prompt and the turn texts out of it.
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

  /** The parsed body of the one request the model sends for {@code messages}. */
  private Map<String, Object> send(List<Message> messages) {
    var requests =
        ModelHarness.exchange(
            List.of(Golden.read(reply())), modelAt(), model -> model.chat(messages));
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
