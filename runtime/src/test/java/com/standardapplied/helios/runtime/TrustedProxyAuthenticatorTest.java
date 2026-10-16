/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.BlockingModel;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.session.SessionOptions;
import io.helidon.common.socket.PeerInfo;
import io.helidon.http.HeaderNames;
import io.helidon.http.ServerRequestHeaders;
import io.helidon.http.WritableHeaders;
import io.helidon.webserver.http.ServerRequest;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

final class TrustedProxyAuthenticatorTest {

  private static final InetAddress PROXY = InetAddress.getLoopbackAddress();
  private static final InetSocketAddress FROM_PROXY = new InetSocketAddress(PROXY, 40_000);
  private static final InetSocketAddress FROM_CLIENT =
      new InetSocketAddress(InetAddress.ofLiteral("192.0.2.7"), 40_000);
  private static final String SUBJECT = TrustedProxyAuthenticator.DEFAULT_SUBJECT_HEADER;
  private static final String TENANT = TrustedProxyAuthenticator.DEFAULT_TENANT_HEADER;

  private final TrustedProxyAuthenticator authenticator =
      TrustedProxyAuthenticator.newBuilder().withTrustedProxy(PROXY).build();

  @Test
  void theProxysAssertionIsTheIdentity() {
    var authentication =
        authenticator
            .authenticate(
                request(FROM_PROXY, Map.of(TENANT, List.of("acme"), SUBJECT, List.of("alice"))))
            .orElseThrow();

    assertEquals(new Principal("acme", "alice"), authentication.principal());
  }

  @Test
  void theSameHeadersFromAnyOtherPeerAreIgnored() {
    var forged = request(FROM_CLIENT, Map.of(TENANT, List.of("acme"), SUBJECT, List.of("alice")));

    assertTrue(authenticator.authenticate(forged).isEmpty());
  }

  @Test
  void aPeerThatIsNotAnInternetSocketIsIgnored() {
    var local =
        request(
            UnixDomainSocketAddress.of("/run/helios.sock"),
            Map.of(TENANT, List.of("acme"), SUBJECT, List.of("alice")));

    assertTrue(authenticator.authenticate(local).isEmpty());
  }

  @Test
  void aMissingHeaderIsUnauthenticated() {
    assertTrue(
        authenticator
            .authenticate(request(FROM_PROXY, Map.of(SUBJECT, List.of("alice"))))
            .isEmpty());
    assertTrue(
        authenticator.authenticate(request(FROM_PROXY, Map.of(TENANT, List.of("acme")))).isEmpty());
  }

  @Test
  void aRepeatedHeaderIsUnauthenticated() {
    var repeated =
        request(FROM_PROXY, Map.of(TENANT, List.of("acme"), SUBJECT, List.of("alice", "admin")));

    assertTrue(authenticator.authenticate(repeated).isEmpty());
  }

  @Test
  void anInvalidPartIsUnauthenticated() {
    var tooLong =
        request(
            FROM_PROXY,
            Map.of(
                TENANT, List.of("acme"), SUBJECT, List.of("a".repeat(Principal.MAX_LENGTH + 1))));

    assertTrue(authenticator.authenticate(tooLong).isEmpty());
  }

  @Test
  void configuredHeaderNamesReplaceTheDefaults() {
    var custom =
        TrustedProxyAuthenticator.newBuilder()
            .withTrustedProxy(PROXY)
            .withSubjectHeader("X-User")
            .withTenantHeader("X-Org")
            .build();

    var viaCustom =
        request(FROM_PROXY, Map.of("X-Org", List.of("acme"), "X-User", List.of("alice")));
    var viaDefaults =
        request(FROM_PROXY, Map.of(TENANT, List.of("acme"), SUBJECT, List.of("alice")));

    assertEquals(
        new Principal("acme", "alice"), custom.authenticate(viaCustom).orElseThrow().principal());
    assertTrue(custom.authenticate(viaDefaults).isEmpty());
  }

  @Test
  void aBuilderWithoutATrustedProxyIsRefused() {
    var builder = TrustedProxyAuthenticator.newBuilder();
    var ex = assertThrows(IllegalStateException.class, builder::build);
    assertEquals(
        "at least one trusted proxy is required — call withTrustedProxy before build",
        ex.getMessage());
  }

  @Test
  void oneHeaderForBothPartsIsRefused() {
    var builder =
        TrustedProxyAuthenticator.newBuilder()
            .withTrustedProxy(PROXY)
            .withSubjectHeader("X-Identity")
            .withTenantHeader("x-identity");
    var ex = assertThrows(IllegalStateException.class, builder::build);
    assertEquals(
        "subject and tenant must come from different headers; both are X-Identity",
        ex.getMessage());
  }

  @Test
  void invalidBuilderArgumentsAreRefused() {
    var builder = TrustedProxyAuthenticator.newBuilder();
    assertEquals(
        "proxy must not be null",
        assertThrows(NullPointerException.class, () -> builder.withTrustedProxy(null))
            .getMessage());
    assertEquals(
        "subject header must be non-blank",
        assertThrows(IllegalArgumentException.class, () -> builder.withSubjectHeader(" "))
            .getMessage());
    assertEquals(
        "tenant header must be non-blank",
        assertThrows(IllegalArgumentException.class, () -> builder.withTenantHeader(null))
            .getMessage());
  }

  @Test
  void anExternallyBoundRuntimeBehindTheProxyServesOnlyAssertedOwners() throws Exception {
    var http = HttpClient.newBuilder().connectTimeout(Await.HANG_GUARD).build();
    try (http;
        var server =
            RuntimeServer.builder()
                .withRegistry(SessionRegistry.inMemory())
                .withOptionsFactory(
                    request ->
                        SessionOptions.newBuilder()
                            .withModel(idleModel())
                            .withSessionId(request.sessionId())
                            .build())
                .withHost("0.0.0.0")
                .withAuthenticator(authenticator)
                .build()) {
      var base = "http://127.0.0.1:" + server.port() + "/v1/sessions";

      var created = http.send(post(base, "acme", "alice"), BodyHandlers.ofString());
      var sessionId =
          (String)
              JsonMapper.builder().build().readValue(created.body(), Map.class).get("sessionId");
      var unasserted =
          http.send(
              HttpRequest.newBuilder(URI.create(base))
                  .timeout(Await.HANG_GUARD)
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .build(),
              BodyHandlers.ofString());
      var foreign =
          http.send(delete(base + "/" + sessionId, "acme", "bob"), BodyHandlers.ofString());
      var owner =
          http.send(delete(base + "/" + sessionId, "acme", "alice"), BodyHandlers.ofString());

      assertEquals(201, created.statusCode());
      assertEquals(401, unasserted.statusCode());
      assertEquals(
          Optional.of("TrustedProxy realm=\"helios\""),
          unasserted.headers().firstValue("WWW-Authenticate"));
      assertEquals(404, foreign.statusCode());
      assertEquals(204, owner.statusCode());
    }
  }

  @Test
  void aConfiguredChallengeIsWhatEvery401Carries() throws Exception {
    var challenging =
        TrustedProxyAuthenticator.newBuilder()
            .withTrustedProxy(PROXY)
            .withChallenge("Proxy realm=\"corp\"")
            .build();
    var http = HttpClient.newBuilder().connectTimeout(Await.HANG_GUARD).build();
    try (http;
        var server =
            RuntimeServer.builder()
                .withRegistry(SessionRegistry.inMemory())
                .withOptionsFactory(
                    request -> {
                      throw new AssertionError("no session is created");
                    })
                .withAuthenticator(challenging)
                .build()) {
      var unasserted =
          http.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + server.port() + "/v1/sessions"))
                  .timeout(Await.HANG_GUARD)
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .build(),
              BodyHandlers.ofString());

      assertEquals(401, unasserted.statusCode());
      assertEquals(
          Optional.of("Proxy realm=\"corp\""), unasserted.headers().firstValue("WWW-Authenticate"));
    }
  }

  @ParameterizedTest
  @MethodSource("com.standardapplied.helios.runtime.AuthenticationGateTest#invalidChallenges")
  void anInvalidChallengeFailsTheBuild(String challenge, String message) {
    var builder =
        TrustedProxyAuthenticator.newBuilder().withTrustedProxy(PROXY).withChallenge(challenge);
    var ex = assertThrows(IllegalArgumentException.class, builder::build);
    assertEquals(message, ex.getMessage());
  }

  @ParameterizedTest
  @MethodSource("com.standardapplied.helios.runtime.AuthenticationGateTest#validChallenges")
  void aValidChallengeIsTheAuthenticatorsChallenge(String challenge) {
    var built =
        TrustedProxyAuthenticator.newBuilder()
            .withTrustedProxy(PROXY)
            .withChallenge(challenge)
            .build();
    assertEquals(challenge, built.challenge());
  }

  @Test
  void aNullChallengeIsRefused() {
    var builder = TrustedProxyAuthenticator.newBuilder();
    var ex = assertThrows(NullPointerException.class, () -> builder.withChallenge(null));
    assertEquals("challenge must not be null", ex.getMessage());
  }

  private static HttpRequest post(String uri, String tenant, String subject) {
    return asserted(uri, tenant, subject).POST(HttpRequest.BodyPublishers.ofString("{}")).build();
  }

  private static HttpRequest delete(String uri, String tenant, String subject) {
    return asserted(uri, tenant, subject).DELETE().build();
  }

  private static HttpRequest.Builder asserted(String uri, String tenant, String subject) {
    return HttpRequest.newBuilder(URI.create(uri))
        .timeout(Await.HANG_GUARD)
        .header("Content-Type", "application/json")
        .header(TENANT, tenant)
        .header(SUBJECT, subject);
  }

  private static BlockingModel idleModel() {
    return new BlockingModel() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        throw new AssertionError("no message is sent in this test");
      }

      @Override
      public String id() {
        return "idle";
      }

      @Override
      public String provider() {
        return "test";
      }
    };
  }

  /** A request from {@code peer} carrying {@code headers}; nothing else of it may be read. */
  private static ServerRequest request(SocketAddress peer, Map<String, List<String>> headers) {
    var writable = WritableHeaders.create();
    headers.forEach(
        (name, values) -> writable.add(HeaderNames.create(name), values.toArray(String[]::new)));
    var requestHeaders = ServerRequestHeaders.create(writable);
    var peerInfo = only(PeerInfo.class, "address", peer);
    return (ServerRequest)
        Proxy.newProxyInstance(
            ServerRequest.class.getClassLoader(),
            new Class<?>[] {ServerRequest.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "remotePeer" -> peerInfo;
                  case "headers" -> requestHeaders;
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  private static <T> T only(Class<T> type, String methodName, Object answer) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> {
              if (method.getName().equals(methodName)) {
                return answer;
              }
              throw new UnsupportedOperationException(method.getName());
            }));
  }
}
