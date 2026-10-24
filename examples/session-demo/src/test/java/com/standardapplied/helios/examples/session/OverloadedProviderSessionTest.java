/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.examples.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.anthropic.AnthropicException;
import com.standardapplied.helios.core.fault.Backoff;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.TransientStreamException;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.Golden;
import com.standardapplied.helios.core.test.SseReplies;
import com.standardapplied.helios.core.test.StubHttpServer;
import com.standardapplied.helios.gemini.GeminiException;
import com.standardapplied.helios.openai.OpenAIException;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SerializedError;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.session.StreamRetryPolicy;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.test.CollectingSubscriber;
import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A session retries a turn whose stream request the provider answered with a retryable status,
 * through the real provider client, and waits the {@code Retry-After} the answer carried. Each
 * provider answers with its documented overload status and error body (Anthropic 529 {@code
 * overloaded_error}, Gemini 503 {@code UNAVAILABLE}, OpenAI 503 {@code server_is_overloaded}),
 * hand-built from its error documentation; a served retry gets {@code
 * <provider>/sessions/held-text.sse} without its hold marker.
 */
final class OverloadedProviderSessionTest {

  @ParameterizedTest
  @MethodSource("overloads")
  void anOverloadedProviderIsRetriedAndThenServesTheTurn(Overload overload) throws IOException {
    var served =
        SseReplies.stream(
            Golden.read(overload.provider() + "/sessions/held-text.sse")
                .replace(StreamingSessionTest.HOLD, ""));
    var events = new CollectingSubscriber();
    try (var server = serving(SseReplies.inOrder(overload.reply(Map.of()), served));
        var model = StreamingSessionTest.modelAt(overload.provider(), server.uri());
        var session = AgentSession.create(retrying(model, 3, Duration.ZERO))) {
      session.events().subscribe(events);
      session.send(UserMessage.text("Hi"));

      var success =
          assertInstanceOf(ResultMessage.Success.class, StreamingSessionTest.result(session));
      events.awaitDone();

      assertEquals("Hello, world.", success.result());
      var retried = events.eventsOf(QueryEvent.TurnRetried.class);
      assertEquals(1, retried.size());
      assertEquals(1, retried.getFirst().attemptNumber());
      assertEquals(overload.provider(), retried.getFirst().providerName());
      overload.assertCause(retried.getFirst().error());
      assertEquals(2, server.requests().size());
    }
  }

  @ParameterizedTest
  @MethodSource("overloads")
  void theRetryWaitsTheRetryAfterTheProviderSent(Overload overload) throws IOException {
    var rateLimited =
        new StubHttpServer.Reply(429, Map.of("Retry-After", "2"), overload.reply(Map.of()).body());
    try (var server = serving(SseReplies.inOrder(rateLimited));
        var model = StreamingSessionTest.modelAt(overload.provider(), server.uri())) {
      var session = AgentSession.create(retrying(model, 100, Duration.ofHours(1)));
      var retries = StreamingSessionTest.queued(session, QueryEvent.TurnRetried.class);
      session.send(UserMessage.text("Hi"));

      var first = Await.next("the first retry", retries);
      session.close();

      assertEquals(Duration.ofSeconds(2), first.backoff());
      assertInstanceOf(ResultMessage.Cancelled.class, StreamingSessionTest.result(session));
    }
  }

  @ParameterizedTest
  @MethodSource("overloads")
  void aBadRequestIsNotRetriedWhateverItsRetryAfter(Overload overload) throws IOException {
    var refused = new StubHttpServer.Reply(400, Map.of("Retry-After", "0"), "{\"error\":\"bad\"}");
    var events = new CollectingSubscriber();
    try (var server = serving(SseReplies.inOrder(refused));
        var model = StreamingSessionTest.modelAt(overload.provider(), server.uri());
        var session = AgentSession.create(SessionOptions.newBuilder().withModel(model).build())) {
      session.events().subscribe(events);
      session.send(UserMessage.text("Hi"));

      var failed =
          assertInstanceOf(
              ResultMessage.ErrorDuringExecution.class, StreamingSessionTest.result(session));
      events.awaitDone();

      assertEquals(overload.exception().getName(), failed.error().kind());
      assertEquals(List.of(), events.eventsOf(QueryEvent.TurnRetried.class));
      assertEquals(1, server.requests().size());
    }
  }

  @ParameterizedTest
  @MethodSource("overloads")
  void anOverloadThatOutlastsTheRetriesEndsTheSessionAsATransientFailure(Overload overload)
      throws IOException {
    var events = new CollectingSubscriber();
    try (var server = serving(SseReplies.inOrder(overload.reply(Map.of())));
        var model = StreamingSessionTest.modelAt(overload.provider(), server.uri());
        var session = AgentSession.create(retrying(model, 2, Duration.ZERO))) {
      session.events().subscribe(events);
      session.send(UserMessage.text("Hi"));

      var failed =
          assertInstanceOf(
              ResultMessage.ErrorTransientStream.class, StreamingSessionTest.result(session));
      events.awaitDone();

      assertEquals(2, failed.attemptsMade());
      assertEquals(overload.provider(), failed.providerName());
      overload.assertCause(failed.error());
      assertEquals(1, events.eventsOf(QueryEvent.TurnRetried.class).size());
      assertEquals(2, server.requests().size());
    }
  }

  /** A provider's documented overload: its status, error body and exception type. */
  record Overload(String provider, int status, String body, Class<?> exception) {

    StubHttpServer.Reply reply(Map<String, String> headers) {
      return new StubHttpServer.Reply(status, headers, body);
    }

    /** {@code error} is a transient failure whose cause is this provider's status exception. */
    void assertCause(SerializedError error) {
      assertEquals(TransientStreamException.class.getName(), error.kind());
      assertEquals(exception.getName(), error.cause().kind());
      assertTrue(error.cause().message().startsWith("API error (status " + status + ")"));
    }
  }

  static Stream<Overload> overloads() {
    return Stream.of(
        new Overload(
            "anthropic",
            529,
            "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}",
            AnthropicException.class),
        new Overload(
            "gemini",
            503,
            "{\"error\":{\"code\":503,\"message\":\"The model is overloaded.\",\"status\":\"UNAVAILABLE\"}}",
            GeminiException.class),
        new Overload(
            "openai",
            503,
            "{\"error\":{\"message\":\"The server is overloaded.\",\"type\":\"server_error\",\"code\":\"server_is_overloaded\"}}",
            OpenAIException.class));
  }

  private static StubHttpServer serving(
      Function<StubHttpServer.Request, StubHttpServer.Reply> replies) {
    return StubHttpServer.start(InetAddress.getLoopbackAddress(), 0, replies);
  }

  private static SessionOptions retrying(Model model, int attempts, Duration backoff) {
    var policy = new StreamRetryPolicy(attempts, Backoff.fixed(backoff), 0.0);
    return SessionOptions.newBuilder()
        .withModel(model)
        .withLimits(SessionLimits.newBuilder().withStreamRetryPolicy(policy).build())
        .build();
  }
}
