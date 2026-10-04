/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.ProviderException;
import com.standardapplied.helios.core.model.StreamEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * An origin that answers every request with a redirect, and the servers that redirect points at,
 * each recording what reaches it: the contract every credentialed provider client is held to. A
 * provider pointed at the origin sends {@link #API_KEY}, {@link #CUSTOM_CREDENTIAL} and {@link
 * #PROMPT}; the {@code assert*} methods prove the origin received exactly that one request, that
 * nothing was forwarded anywhere else, and that the error reports the status and no secret.
 */
public final class RedirectTrap implements AutoCloseable {

  /** The dummy API key a trapped provider is configured with. */
  public static final String API_KEY = "dummy-api-key-0123456789";

  /** The custom credential header a trapped provider is configured with. */
  public static final String CUSTOM_HEADER = "X-Gateway-Token";

  /** The value of {@link #CUSTOM_HEADER}. */
  public static final String CUSTOM_CREDENTIAL = "dummy-gateway-token-9876543210";

  /** The prompt a trapped provider sends; it must never reach a redirect target. */
  public static final String PROMPT = "confidential-prompt-body-4242";

  /** The {@code @MethodSource} naming {@link #scenarios()}. */
  public static final String SCENARIOS =
      "com.standardapplied.helios.core.test.RedirectTrap#scenarios";

  /** Where the origin's {@code Location} header points, or what is wrong with it. */
  public enum Shape {
    /** Another loopback host on the origin's port. */
    HOST_CHANGE,
    /** The origin's host on another port. */
    PORT_CHANGE,
    /** {@code https} instead of the origin's {@code http}. */
    SCHEME_CHANGE,
    /** A hop that itself redirects to the target. */
    CHAINED,
    /** The origin itself. */
    LOOP,
    /** A {@code Location} that is not a URI. */
    MALFORMED_LOCATION,
    /** No {@code Location} at all. */
    MISSING_LOCATION
  }

  /** One redirect status with one {@link Shape}. */
  public record Scenario(int status, Shape shape) {}

  private static final List<Integer> STATUSES = List.of(301, 302, 303, 307, 308);
  private static final InetAddress HOST = loopback(1);
  private static final InetAddress OTHER_HOST = loopback(2);
  private static final String PATH = "/v1";

  private final Scenario scenario;
  private final StubHttpServer target;
  private final StubHttpServer hop;
  private final StubHttpServer origin;

  private RedirectTrap(Scenario scenario) {
    this.scenario = scenario;
    var hostChange = scenario.shape() == Shape.HOST_CHANGE;
    this.target =
        StubHttpServer.start(hostChange ? OTHER_HOST : HOST, 0, request -> reply(200, Map.of()));
    this.hop = StubHttpServer.start(HOST, 0, request -> redirect(target.uri() + PATH));
    this.origin =
        StubHttpServer.start(
            HOST, hostChange ? target.port() : 0, request -> redirect(location(scenario.shape())));
  }

  /** Every redirect status with every {@link Shape}. */
  public static Stream<Scenario> scenarios() {
    return STATUSES.stream()
        .flatMap(status -> Arrays.stream(Shape.values()).map(shape -> new Scenario(status, shape)));
  }

  /**
   * Start a trap for {@code scenario}.
   *
   * @param scenario the status and shape the origin answers with
   * @return the running trap
   */
  public static RedirectTrap open(Scenario scenario) {
    return new RedirectTrap(scenario);
  }

  /** The origin as a provider base URL. */
  public String originUrl() {
    return origin.uri() + PATH;
  }

  /**
   * Assert {@code chat} on a model built against the origin fails with {@code errorType} carrying
   * the redirect status, having forwarded nothing.
   *
   * @param scenario the redirect the origin answers with
   * @param provider builds the provider's model from a config
   * @param errorType the provider's error type
   * @throws IOException if a fixture server fails to close
   */
  public static void assertChatRefused(
      Scenario scenario,
      Function<ModelConfig, Model> provider,
      Class<? extends ProviderException> errorType)
      throws IOException {
    try (var trap = open(scenario);
        var model = provider.apply(trap.config())) {
      var error = assertThrows(errorType, () -> model.chat(List.of(Message.user(PROMPT))));
      trap.assertRefused(error, error.getMessage());
    }
  }

  /**
   * Assert {@code chatStream} on a model built against the origin yields one error event whose
   * cause is {@code errorType} carrying the redirect status, having forwarded nothing.
   *
   * @param scenario the redirect the origin answers with
   * @param provider builds the provider's model from a config
   * @param errorType the provider's error type
   * @throws IOException if a fixture server fails to close
   */
  public static void assertChatStreamRefused(
      Scenario scenario,
      Function<ModelConfig, Model> provider,
      Class<? extends ProviderException> errorType)
      throws IOException {
    try (var trap = open(scenario);
        var model = provider.apply(trap.config());
        var stream = model.chatStream(List.of(Message.user(PROMPT)))) {
      var event = assertInstanceOf(StreamEvent.Error.class, stream.next());
      assertFalse(stream.hasNext(), "events after the error");
      trap.assertRefused(assertInstanceOf(errorType, event.cause()), event.message());
    }
  }

  /**
   * Assert two refused redirects reach the origin over one connection: the first refusal released
   * its response, or the second request could not have reused the connection.
   *
   * @param provider builds the provider's model from a config
   * @param errorType the provider's error type
   * @throws IOException if a fixture server fails to close
   */
  public static void assertRefusalReleasesConnection(
      Function<ModelConfig, Model> provider, Class<? extends ProviderException> errorType)
      throws IOException {
    try (var trap = open(new Scenario(307, Shape.PORT_CHANGE));
        var model = provider.apply(trap.config())) {
      for (var call = 0; call < 2; call++) {
        assertThrows(errorType, () -> model.chat(List.of(Message.user(PROMPT))));
      }
      assertEquals(2, trap.origin.requests().size(), "origin requests");
      assertEquals(1, trap.origin.connections(), "origin connections");
    }
  }

  /**
   * Assert the origin received exactly one request, carrying {@link #API_KEY}, {@link
   * #CUSTOM_CREDENTIAL} and {@link #PROMPT}, and no other server received any request.
   */
  public void assertNothingForwarded() {
    assertEquals(List.of(), target.requests(), "redirect target received a request");
    assertEquals(List.of(), hop.requests(), "chained hop received a request");
    var received = origin.requests();
    assertEquals(1, received.size(), "origin requests");
    var headers = received.getFirst().headers();
    assertTrue(headers.values().stream().anyMatch(value -> value.contains(API_KEY)), "API key");
    assertEquals(CUSTOM_CREDENTIAL, headers.get(CUSTOM_HEADER.toLowerCase(Locale.ROOT)));
    assertTrue(received.getFirst().body().contains(PROMPT), "prompt in request body");
  }

  private ModelConfig config() {
    return ModelConfig.newBuilder()
        .withApiKey(API_KEY)
        .withBaseUrl(originUrl())
        .withHeader(CUSTOM_HEADER, CUSTOM_CREDENTIAL)
        .build();
  }

  private void assertRefused(ProviderException error, String reported) {
    assertNothingForwarded();
    assertEquals(scenario.status(), error.statusCode(), "status code");
    assertFalse(error.isRetryable(), "a refused redirect is retryable");
    for (var message : List.of(reported, error.getMessage())) {
      assertTrue(message.contains(String.valueOf(scenario.status())), "status in: " + message);
      for (var secret : List.of(API_KEY, CUSTOM_CREDENTIAL, PROMPT)) {
        assertFalse(message.contains(secret), "secret echoed in: " + message);
      }
    }
  }

  @Override
  public void close() throws IOException {
    origin.close();
    hop.close();
    target.close();
  }

  private String location(Shape shape) {
    return switch (shape) {
      case HOST_CHANGE, PORT_CHANGE -> target.uri() + PATH;
      case SCHEME_CHANGE -> "https://" + target.uri().getAuthority() + PATH;
      case CHAINED -> hop.uri() + PATH;
      case LOOP -> PATH;
      case MALFORMED_LOCATION -> "http://[not a uri" + PATH;
      case MISSING_LOCATION -> null;
    };
  }

  private static InetAddress loopback(int last) {
    try {
      return InetAddress.getByAddress(new byte[] {127, 0, 0, (byte) last});
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private StubHttpServer.Reply redirect(String location) {
    return reply(scenario.status(), location == null ? Map.of() : Map.of("Location", location));
  }

  private static StubHttpServer.Reply reply(int status, Map<String, String> headers) {
    return new StubHttpServer.Reply(status, headers, "redirect body " + status);
  }
}
