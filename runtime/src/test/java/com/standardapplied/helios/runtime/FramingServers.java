/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.testing.ScriptedModel;
import io.helidon.http.Header;
import io.helidon.http.HeaderValues;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.WebServerConfig;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.http1.Http1Config;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The servers a framing test sends raw requests to: a {@link RuntimeServer}, and an {@link
 * AgentHttpService} on a plain server of its own with the same two Helidon settings, over one
 * registry holding a session each of alice, bob and carol. Their authenticator trusts the loopback
 * peer, counts every request from the smuggler, an identity no client may claim, and throws an
 * {@link Error} for a request marked {@code X-Crash: yes}.
 */
final class FramingServers implements AutoCloseable {

  /** Above the smuggled request's length, so that only the {@code 413} cases exceed it. */
  static final int MAX_BODY = 256;

  /**
   * Requests of one principal in flight at a time: above one, so an earlier request whose slot is
   * not yet given back never turns a later one into a refusal.
   */
  static final int REQUESTS_PER_PRINCIPAL = 2;

  /** A complete request, sent as the body of another, from an identity no client may claim. */
  static final String SMUGGLED =
      "POST /v1/sessions HTTP/1.1\r\nHost: 127.0.0.1\r\nX-Authenticated-Tenant: evil\r\n"
          + "X-Authenticated-Subject: smuggler\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";

  private static final Header SMUGGLER =
      HeaderValues.create(TrustedProxyAuthenticator.DEFAULT_SUBJECT_HEADER, "smuggler");

  private static final Header CRASH = HeaderValues.create("X-Crash", "yes");

  final AtomicInteger smuggledSeen = new AtomicInteger();
  final SessionRegistry registry = registry();
  final RuntimeServer runtime =
      RuntimeServer.builder()
          .withRegistry(registry)
          .withOptionsFactory(FramingServers::options)
          .withRequestLimits(new RequestLimits(MAX_BODY, REQUESTS_PER_PRINCIPAL))
          .withAuthenticator(authenticator())
          .build();
  final WebServer own = deploy(registry, 512, FramingServers::bounded);

  /** Session ids by the subject that owns them. */
  final Map<String, String> sessions =
      Map.of("alice", create("alice"), "bob", create("bob"), "carol", create("carol"));

  /**
   * {@link AgentHttpService} over {@code registry} on a plain server, configured further by {@code
   * settings}.
   */
  WebServer deploy(
      SessionRegistry registry,
      int maxConcurrentRequests,
      Consumer<WebServerConfig.Builder> settings) {
    var service =
        new AgentHttpService(
            registry,
            FramingServers::options,
            authenticator(),
            "/v1",
            new RequestLimits(MAX_BODY, REQUESTS_PER_PRINCIPAL));
    var builder =
        WebServer.builder()
            .host("127.0.0.1")
            .port(0)
            .maxConcurrentRequests(maxConcurrentRequests)
            .featuresDiscoverServices(false)
            .routing((HttpRouting.Builder routing) -> routing.register("/v1", service));
    settings.accept(builder);
    return builder.build().start();
  }

  /** End every session's event streams, then stop both servers. */
  @Override
  public void close() {
    sessions.forEach(
        (name, id) ->
            ((StubSession) registry.find(id, new Principal("acme", name)).orElseThrow())
                .completeEvents());
    own.stop();
    runtime.close();
  }

  static SessionRegistry registry() {
    return SessionRegistry.newBuilder()
        .withFactory(options -> new StubSession(options.sessionId(), 8))
        .build();
  }

  static SessionOptions options(SessionRequest request) {
    return SessionOptions.newBuilder()
        .withModel(ScriptedModel.newBuilder().withTextTurn("x").build())
        .withSessionId(request.sessionId())
        .build();
  }

  static String identity(String principal) {
    return "X-Authenticated-Tenant: acme\r\nX-Authenticated-Subject: " + principal + "\r\n";
  }

  static String messages(String sessionId) {
    return "/v1/sessions/" + sessionId + "/messages";
  }

  /** The two Helidon settings a host's own server sets, as {@link RuntimeServer} does. */
  private static void bounded(WebServerConfig.Builder builder) {
    builder
        .maxPayloadSize(MAX_BODY)
        .addProtocol(Http1Config.builder().continueImmediately(true).build());
  }

  private Authenticator authenticator() {
    var trusted =
        TrustedProxyAuthenticator.newBuilder()
            .withTrustedProxy(InetAddress.getLoopbackAddress())
            .build();
    return new TestAuthenticator(
        request -> {
          if (request.headers().contains(SMUGGLER)) {
            smuggledSeen.incrementAndGet();
          }
          if (request.headers().contains(CRASH)) {
            throw new AssertionError("an Error escaping a route");
          }
          return trusted.authenticate(request);
        });
  }

  private String create(String principal) {
    try (var connection = RawConnection.open(runtime.port())) {
      connection.write(
          "POST /v1/sessions HTTP/1.1\r\nHost: 127.0.0.1\r\n" + identity(principal) + "\r\n");
      var created = connection.response();
      assertEquals("HTTP/1.1 201 Created", created.statusLine(), created::body);
      return (String)
          JsonMapper.builder().build().readValue(created.body(), Map.class).get("sessionId");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
