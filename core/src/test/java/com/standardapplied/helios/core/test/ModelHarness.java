/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Runs a provider {@link Model} against a {@link StubHttpServer} that replays recorded responses,
 * through the public {@code Model} API only, so what it records survives any restructuring of the
 * provider behind that API.
 */
public final class ModelHarness {

  /** The line that separates two responses in a recorded fixture. */
  public static final String NEXT_RESPONSE = "\n=== next response ===\n";

  private static final List<Message> PROMPT = List.of(Message.user("Hi"));

  private ModelHarness() {}

  /**
   * Builds the model against a stub answering with {@code replies}, runs {@code call} on it, and
   * returns every request the model sent.
   */
  public static List<StubHttpServer.Request> exchange(
      List<String> replies, Function<URI, Model> modelAt, Consumer<Model> call) {
    try (var server =
            StubHttpServer.start(InetAddress.getLoopbackAddress(), 0, SseReplies.inOrder(replies));
        var model = modelAt.apply(server.uri())) {
      call.accept(model);
      return server.requests();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * The transcript of the recorded golden {@code fixture}: every event {@code chatStream} yields,
   * then what {@code chat} returns or throws and the body of every follow-up request it sent.
   */
  public static String transcript(String fixture, Function<URI, Model> modelAt) {
    return transcript(fixture, modelAt, UnaryOperator.identity());
  }

  /**
   * {@link #transcript(String, Function)} with every string held in a map, and every follow-up
   * request body, rendered through {@code canonical}.
   */
  public static String transcript(
      String fixture, Function<URI, Model> modelAt, UnaryOperator<String> canonical) {
    var render = new Transcript(canonical);
    var replies = Arrays.asList(Golden.read(fixture).split(NEXT_RESPONSE));
    var text = new StringBuilder("== chatStream\n");
    exchange(
        replies, modelAt, model -> text.append(render.events(model.chatStream(PROMPT, List.of()))));
    text.append("== chat\n");
    var requests =
        exchange(
            replies,
            modelAt,
            model -> text.append(render.outcome(() -> model.chat(PROMPT, List.of()))));
    for (var request : requests.subList(1, requests.size())) {
      text.append("== follow-up request\n").append(canonical.apply(request.body())).append('\n');
    }
    return text.toString();
  }
}
