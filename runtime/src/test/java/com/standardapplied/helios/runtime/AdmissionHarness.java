/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    var server =
        RuntimeServer.builder()
            .withRegistry(registry.build())
            .withOptionsFactory(this::options)
            .withAuthenticator(AdmissionHarness::authenticate)
            .withRequestLimits(limits)
            .build();
    servers.add(server);
    return server;
  }

  String create(RuntimeServer server, String token) {
    var resp = send(server, "POST", "/sessions", token);
    assertEquals(201, resp.statusCode(), resp::body);
    return (String) mapper.readValue(resp.body(), Map.class).get("sessionId");
  }

  HttpResponse<String> send(RuntimeServer server, String method, String path, String token) {
    return Await.value("the request to be answered", sendAsync(server, method, path, token));
  }

  HttpResponse<String> send(
      RuntimeServer server, String method, String path, String token, BodyPublisher body) {
    return Await.value(
        "the request to be answered",
        http.sendAsync(request(server, method, path, token, body), BodyHandlers.ofString()));
  }

  CompletableFuture<HttpResponse<String>> sendAsync(
      RuntimeServer server, String method, String path, String token) {
    var body = method.equals("POST") ? BodyPublishers.ofByteArray(BODY) : BodyPublishers.noBody();
    return http.sendAsync(request(server, method, path, token, body), BodyHandlers.ofString());
  }

  /** Open {@code sessionId}'s event stream; the future completes when its headers arrive. */
  CompletableFuture<HttpResponse<InputStream>> openEvents(
      RuntimeServer server, String sessionId, String token) {
    return http.sendAsync(
        request(server, "GET", events(sessionId), token, BodyPublishers.noBody()),
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

  private static HttpRequest request(
      RuntimeServer server, String method, String path, String token, BodyPublisher body) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1" + path))
        .timeout(Await.HANG_GUARD)
        .header("Authorization", "Bearer " + token)
        .header("Content-Type", "application/json")
        .method(method, body)
        .build();
  }
}
