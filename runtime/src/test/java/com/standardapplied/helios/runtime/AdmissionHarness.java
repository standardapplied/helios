/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.testing.ScriptedModel;
import java.io.BufferedReader;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runtime servers over {@link StubSession}s for the admission tests, and an HTTP client to drive
 * them. Requests authenticate through {@link #tokens}. Each session's steering queue holds two
 * messages. Closing the harness ends every stream and result and stops every server.
 */
final class AdmissionHarness implements AutoCloseable {

  /** Requests sent at once in a burst. */
  static final int BURST = 6;

  /** A message and interrupt body of 32 bytes. */
  static final byte[] BODY = textBody(32);

  /** The body of a request refused because its principal has every request slot in flight. */
  static final String TOO_MANY_REQUESTS = "{\"error\":\"too many requests in progress\"}";

  final Map<String, StubSession> sessions = new ConcurrentHashMap<>();
  final AtomicReference<Runnable> duringFactory = new AtomicReference<>(() -> {});
  final AtomicInteger factoryRuns = new AtomicInteger();
  final RevocableTokens tokens = new RevocableTokens();
  private final JsonMapper mapper = JsonMapper.builder().build();
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Await.HANG_GUARD).build();
  private final List<HttpClient> ownConnections = new ArrayList<>();
  private final List<RuntimeServer> servers = new ArrayList<>();

  SessionRegistry.Builder registry() {
    return SessionRegistry.newBuilder()
        .withFactory(
            opts -> {
              var session = new StubSession(opts.sessionId(), 2);
              sessions.put(opts.sessionId(), session);
              return session;
            });
  }

  /** A server with the default {@link RequestLimits}. */
  RuntimeServer server(SessionRegistry.Builder registry) {
    return start(registry, UnaryOperator.identity());
  }

  RuntimeServer server(SessionRegistry.Builder registry, RequestLimits limits) {
    return start(registry, builder -> builder.withRequestLimits(limits));
  }

  RuntimeServer server(SessionRegistry.Builder registry, int requestsPerPrincipal) {
    return server(registry, limits(requestsPerPrincipal));
  }

  String create(RuntimeServer server, String token) {
    var resp = sendAdmitted(server, "POST", "/sessions", token);
    assertEquals(201, resp.statusCode(), resp::body);
    return (String) mapper.readValue(resp.body(), Map.class).get("sessionId");
  }

  /**
   * Send until the principal's request bound admits the request. A request gives its slot back just
   * after its response is sent, so a request sent as soon as the client has that response may still
   * find the slot held.
   */
  HttpResponse<String> sendAdmitted(
      RuntimeServer server, String method, String path, String token, BodyPublisher body) {
    var answer = new AtomicReference<HttpResponse<String>>();
    Await.until(
        "the request to be admitted",
        () -> {
          answer.set(send(server, method, path, token, body));
          return !isRequestRefusal(answer.get().statusCode(), answer.get().body());
        });
    return answer.get();
  }

  /** {@link #sendAdmitted} with the default body for {@code method}. */
  HttpResponse<String> sendAdmitted(
      RuntimeServer server, String method, String path, String token) {
    return sendAdmitted(server, method, path, token, defaultBody(method));
  }

  /**
   * Open {@code sessionId}'s event stream once the request bound admits it, and read its {@code
   * Ready} event, so the stream is subscribed and {@link #close()} ends it.
   *
   * @return the rest of the stream
   */
  BufferedReader openEventsAdmitted(RuntimeServer server, String sessionId, String token) {
    return admitted(() -> openEvents(server, sessionId, token));
  }

  /**
   * {@link #openEventsAdmitted} on a connection of its own, as {@link #openEventsOnOwnConnection}.
   */
  BufferedReader openEventsOnOwnConnectionAdmitted(
      RuntimeServer server, String sessionId, String token) {
    return admitted(() -> openEventsOnOwnConnection(server, sessionId, token));
  }

  private BufferedReader admitted(Supplier<CompletableFuture<HttpResponse<InputStream>>> open) {
    var answer = new AtomicReference<HttpResponse<InputStream>>();
    Await.until(
        "the event stream to be admitted",
        () -> {
          var resp = Await.value("the event stream to be answered", open.get());
          if (resp.statusCode() == 200) {
            answer.set(resp);
            return true;
          }
          var text = ResponseBodies.text(resp.body());
          assertTrue(isRequestRefusal(resp.statusCode(), text), text);
          return false;
        });
    return ResponseBodies.afterReady(answer.get());
  }

  HttpResponse<String> send(RuntimeServer server, String method, String path, String token) {
    return Await.value("the request to be answered", sendAsync(server, method, path, token));
  }

  HttpResponse<String> send(
      RuntimeServer server, String method, String path, String token, BodyPublisher body) {
    return Await.value(
        "the request to be answered",
        http.sendAsync(
            builder(server, method, path, token, body).build(), BodyHandlers.ofString()));
  }

  CompletableFuture<HttpResponse<String>> sendAsync(
      RuntimeServer server, String method, String path, String token) {
    return http.sendAsync(
        builder(server, method, path, token, defaultBody(method)).build(), BodyHandlers.ofString());
  }

  /**
   * POST {@code body} with {@code Expect: 100-continue}: the server sends {@code 100} only once its
   * handler starts reading the body, so the client reads {@code body} only after the upload was
   * admitted, and never for a request refused before its body.
   */
  CompletableFuture<HttpResponse<String>> upload(
      RuntimeServer server, String path, String token, InputStream body) {
    return upload(server, path, token, BodyPublishers.ofInputStream(() -> body));
  }

  /** {@link #upload(RuntimeServer, String, String, InputStream)} with any body publisher. */
  CompletableFuture<HttpResponse<String>> upload(
      RuntimeServer server, String path, String token, BodyPublisher body) {
    return http.sendAsync(
        builder(server, "POST", path, token, body)
            .version(HttpClient.Version.HTTP_1_1)
            .expectContinue(true)
            .build(),
        BodyHandlers.ofString());
  }

  /** Open {@code sessionId}'s event stream; the future completes when its headers arrive. */
  CompletableFuture<HttpResponse<InputStream>> openEvents(
      RuntimeServer server, String sessionId, String token) {
    return http.sendAsync(
        builder(server, "GET", events(sessionId), token, BodyPublishers.noBody()).build(),
        BodyHandlers.ofInputStream());
  }

  /**
   * {@link #openEvents} on a connection no later request reuses: a request kept alive on the
   * connection of a stream whose handler still runs would wait for that handler, not be admitted or
   * refused.
   */
  CompletableFuture<HttpResponse<InputStream>> openEventsOnOwnConnection(
      RuntimeServer server, String sessionId, String token) {
    var client = HttpClient.newBuilder().connectTimeout(Await.HANG_GUARD).build();
    ownConnections.add(client);
    return client.sendAsync(
        builder(server, "GET", events(sessionId), token, BodyPublishers.noBody()).build(),
        BodyHandlers.ofInputStream());
  }

  @Override
  public void close() {
    sessions.values().forEach(StubSession::completeEvents);
    sessions.values().forEach(StubSession::terminate);
    servers.forEach(RuntimeServer::close);
    http.shutdownNow();
    ownConnections.forEach(HttpClient::shutdownNow);
  }

  /** A body valid for both the message and the interrupt route, exactly {@code bytes} long. */
  static byte[] textBody(int bytes) {
    var envelope = "{\"text\":\"\",\"reason\":\"\"}".length();
    var text = "t".repeat((bytes - envelope) / 2);
    var reason = "r".repeat(bytes - envelope - text.length());
    return ("{\"text\":\"" + text + "\",\"reason\":\"" + reason + "\"}")
        .getBytes(StandardCharsets.UTF_8);
  }

  /** Limits with the default body bound and {@code requestsPerPrincipal} requests in flight. */
  static RequestLimits limits(int requestsPerPrincipal) {
    return new RequestLimits(RequestLimits.defaults().maxRequestBytes(), requestsPerPrincipal);
  }

  /** Assert {@code resp} is a refusal with {@code body} and the retry guidance. */
  static void assertRefused(HttpResponse<String> resp, String body) {
    assertEquals(body, resp.body());
    assertEquals(
        Optional.of(Overload.RETRY_AFTER_SECONDS), resp.headers().firstValue("Retry-After"));
  }

  static String messages(String sessionId) {
    return "/sessions/" + sessionId + "/messages";
  }

  static String interrupt(String sessionId) {
    return "/sessions/" + sessionId + "/interrupt";
  }

  static String events(String sessionId) {
    return "/sessions/" + sessionId + "/events";
  }

  static String result(String sessionId) {
    return "/sessions/" + sessionId + "/result?timeout=300";
  }

  private static boolean isRequestRefusal(int status, String body) {
    return status == 429 && body.equals(TOO_MANY_REQUESTS);
  }

  private static BodyPublisher defaultBody(String method) {
    return method.equals("POST") ? BodyPublishers.ofByteArray(BODY) : BodyPublishers.noBody();
  }

  private RuntimeServer start(
      SessionRegistry.Builder registry, UnaryOperator<RuntimeServer.Builder> configure) {
    var server =
        configure
            .apply(
                RuntimeServer.builder()
                    .withRegistry(registry.build())
                    .withOptionsFactory(
                        request -> {
                          factoryRuns.incrementAndGet();
                          duringFactory.get().run();
                          return SessionOptions.newBuilder()
                              .withModel(ScriptedModel.newBuilder().withTextTurn("x").build())
                              .withSessionId(request.sessionId())
                              .build();
                        })
                    .withAuthenticator(tokens))
            .build();
    servers.add(server);
    return server;
  }

  private static HttpRequest.Builder builder(
      RuntimeServer server, String method, String path, String token, BodyPublisher body) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1" + path))
        .timeout(Await.HANG_GUARD)
        .header("Authorization", "Bearer " + token)
        .header("Content-Type", "application/json")
        .method(method, body);
  }
}
