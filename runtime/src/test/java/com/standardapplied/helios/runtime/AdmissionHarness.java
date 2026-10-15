/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.testing.ScriptedModel;
import io.helidon.http.HeaderNames;
import io.helidon.webserver.http.ServerRequest;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * Runtime servers over {@link StubSession}s for the admission tests, and an HTTP client to drive
 * them. A bearer token {@code <name>} authenticates as subject {@code <name>} of tenant {@code
 * tenant-<name>}. Each session's steering queue holds two messages. Closing the harness ends every
 * stream and result and stops every server.
 */
final class AdmissionHarness implements AutoCloseable {

  /** Requests sent at once in a burst. */
  static final int BURST = 6;

  /** A message and interrupt body of 32 bytes. */
  static final byte[] BODY = textBody(32);

  private static final List<Map<String, String>> CONCURRENCY_REFUSALS =
      List.of(
          Overload.TOO_MANY_UPLOADS,
          Overload.SERVER_AT_UPLOAD_CAPACITY,
          Overload.TOO_MANY_EVENT_STREAMS,
          Overload.TOO_MANY_OPEN_EVENT_STREAMS,
          Overload.SERVER_AT_EVENT_STREAM_CAPACITY,
          Overload.TOO_MANY_RESULT_POLLS,
          Overload.TOO_MANY_WAITING_RESULT_POLLS,
          Overload.SERVER_AT_RESULT_POLL_CAPACITY);

  final Map<String, StubSession> sessions = new ConcurrentHashMap<>();
  final AtomicReference<Runnable> duringFactory = new AtomicReference<>(() -> {});
  final AtomicInteger factoryRuns = new AtomicInteger();
  private final JsonMapper mapper = JsonMapper.builder().build();
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Await.HANG_GUARD).build();
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

  RuntimeServer server(SessionRegistry.Builder registry, RequestLimits limits) {
    return server(registry, limits, JsonMapper.builder().build());
  }

  RuntimeServer server(SessionRegistry.Builder registry, RequestLimits limits, JsonMapper mapper) {
    var server =
        RuntimeServer.builder()
            .withRegistry(registry.build())
            .withOptionsFactory(this::options)
            .withAuthenticator(AdmissionHarness::authenticate)
            .withRequestLimits(limits)
            .withObjectMapper(mapper)
            .build();
    servers.add(server);
    return server;
  }

  String create(RuntimeServer server, String token) {
    var resp = send(server, "POST", "/sessions", token);
    assertEquals(201, resp.statusCode(), resp::body);
    return (String) mapper.readValue(resp.body(), Map.class).get("sessionId");
  }

  /**
   * Send until no concurrency bound refuses the request. A stream, poll or upload gives its
   * admission back just after its response is sent, so a request sent as soon as the client has
   * that response may still find the admission held.
   */
  HttpResponse<String> sendAdmitted(
      RuntimeServer server, String method, String path, String token, BodyPublisher body) {
    var answer = new AtomicReference<HttpResponse<String>>();
    Await.until(
        "the request to be admitted",
        () -> {
          answer.set(send(server, method, path, token, body));
          return !isConcurrencyRefusal(answer.get().statusCode(), answer.get().body());
        });
    return answer.get();
  }

  /** {@link #sendAdmitted} with the default body for {@code method}. */
  HttpResponse<String> sendAdmitted(
      RuntimeServer server, String method, String path, String token) {
    return sendAdmitted(server, method, path, token, defaultBody(method));
  }

  /** Open {@code sessionId}'s event stream once no concurrency bound refuses it. */
  HttpResponse<InputStream> openEventsAdmitted(
      RuntimeServer server, String sessionId, String token) {
    var answer = new AtomicReference<HttpResponse<InputStream>>();
    Await.until(
        "the event stream to be admitted",
        () -> {
          var resp =
              Await.value("the event stream to be answered", openEvents(server, sessionId, token));
          if (resp.statusCode() == 200) {
            answer.set(resp);
            return true;
          }
          var text = ResponseBodies.text(resp.body());
          assertTrue(isConcurrencyRefusal(resp.statusCode(), text), text);
          return false;
        });
    return answer.get();
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
    return http.sendAsync(
        builder(server, "POST", path, token, BodyPublishers.ofInputStream(() -> body))
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

  @Override
  public void close() {
    sessions.values().forEach(StubSession::completeEvents);
    sessions.values().forEach(StubSession::terminate);
    servers.forEach(RuntimeServer::close);
    http.shutdownNow();
  }

  /** A body valid for both the message and the interrupt route, exactly {@code bytes} long. */
  static byte[] textBody(int bytes) {
    var envelope = "{\"text\":\"\",\"reason\":\"\"}".length();
    var text = "t".repeat((bytes - envelope) / 2);
    var reason = "r".repeat(bytes - envelope - text.length());
    return ("{\"text\":\"" + text + "\",\"reason\":\"" + reason + "\"}")
        .getBytes(StandardCharsets.UTF_8);
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

  private boolean isConcurrencyRefusal(int status, String body) {
    return (status == 429 || status == 503)
        && CONCURRENCY_REFUSALS.stream().map(mapper::writeValueAsString).anyMatch(body::equals);
  }

  private static BodyPublisher defaultBody(String method) {
    return method.equals("POST") ? BodyPublishers.ofByteArray(BODY) : BodyPublishers.noBody();
  }

  private SessionOptions options(String sessionId) {
    factoryRuns.incrementAndGet();
    duringFactory.get().run();
    return SessionOptions.newBuilder()
        .withModel(ScriptedModel.newBuilder().withTextTurn("x").build())
        .withSessionId(sessionId)
        .build();
  }

  private static Optional<Authentication> authenticate(ServerRequest request) {
    return request
        .headers()
        .first(HeaderNames.AUTHORIZATION)
        .map(header -> header.substring("Bearer ".length()))
        .map(name -> Authentication.of(new Principal("tenant-" + name, name)));
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
