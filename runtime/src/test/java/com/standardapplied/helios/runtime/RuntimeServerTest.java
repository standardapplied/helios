/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.BlockingModel;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.session.SessionOptions;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

final class RuntimeServerTest {

  private static Model stubModel() {
    return new BlockingModel() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        return Response.newBuilder().withContent("x").build();
      }

      @Override
      public String id() {
        return "stub";
      }

      @Override
      public String provider() {
        return "stub";
      }
    };
  }

  private static java.util.function.Function<SessionRequest, SessionOptions> factory() {
    return request ->
        SessionOptions.newBuilder()
            .withModel(stubModel())
            .withSessionId(request.sessionId())
            .build();
  }

  // ── builder validation ────────────────────────────────────────────────────

  @Test
  void buildWithoutRegistryThrows() {
    var ex =
        assertThrows(
            IllegalStateException.class,
            () -> RuntimeServer.builder().withOptionsFactory(factory()).build());
    assertTrue(ex.getMessage().startsWith("registry is required"));
  }

  @Test
  void buildWithoutOptionsFactoryThrows() {
    var ex =
        assertThrows(
            IllegalStateException.class,
            () -> RuntimeServer.builder().withRegistry(SessionRegistry.inMemory()).build());
    assertTrue(ex.getMessage().startsWith("optionsFactory is required"));
  }

  @Test
  void withRegistryRejectsNull() {
    var ex =
        assertThrows(NullPointerException.class, () -> RuntimeServer.builder().withRegistry(null));
    assertEquals("registry must not be null", ex.getMessage());
  }

  @Test
  void withOptionsFactoryRejectsNull() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> RuntimeServer.builder().withOptionsFactory(null));
    assertEquals("optionsFactory must not be null", ex.getMessage());
  }

  @Test
  void withObjectMapperRejectsNull() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> RuntimeServer.builder().withObjectMapper(null));
    assertEquals("objectMapper must not be null", ex.getMessage());
  }

  @Test
  void withRoutePrefixRejectsNull() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> RuntimeServer.builder().withRoutePrefix(null));
    assertEquals("routePrefix must not be null", ex.getMessage());
  }

  @Test
  void withRoutePrefixRejectsBlank() {
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> RuntimeServer.builder().withRoutePrefix("  "));
    assertTrue(ex.getMessage().startsWith("routePrefix must be non-blank"));
  }

  @Test
  void withRoutePrefixRejectsMissingLeadingSlash() {
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> RuntimeServer.builder().withRoutePrefix("v1"));
    assertTrue(ex.getMessage().contains("start with '/'"));
  }

  @Test
  void withPortRejectsNegative() {
    var ex =
        assertThrows(IllegalArgumentException.class, () -> RuntimeServer.builder().withPort(-1));
    assertTrue(ex.getMessage().contains("[0, 65535]"));
  }

  @Test
  void withPortRejectsTooLarge() {
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> RuntimeServer.builder().withPort(70_000));
    assertTrue(ex.getMessage().contains("[0, 65535]"));
  }

  @Test
  void withHostRejectsNull() {
    var ex = assertThrows(NullPointerException.class, () -> RuntimeServer.builder().withHost(null));
    assertEquals("host must not be null", ex.getMessage());
  }

  @Test
  void withHostRejectsBlank() {
    var ex =
        assertThrows(IllegalArgumentException.class, () -> RuntimeServer.builder().withHost(" "));
    assertEquals("host must not be blank", ex.getMessage());
  }

  @Test
  void withAuthenticatorRejectsNull() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> RuntimeServer.builder().withAuthenticator(null));
    assertEquals("authenticator must not be null", ex.getMessage());
  }

  @Test
  void withRequestLimitsRejectsNull() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> RuntimeServer.builder().withRequestLimits(null));
    assertEquals("requestLimits must not be null", ex.getMessage());
  }

  // ── authentication against the bind address ──────────────────────────────

  @Test
  void aLoopbackServerWithNoAuthenticationChoiceIsRefused() {
    var builder =
        RuntimeServer.builder()
            .withRegistry(SessionRegistry.inMemory())
            .withOptionsFactory(factory());
    var ex = assertThrows(IllegalStateException.class, builder::build);
    assertEquals(
        "an authenticator is required — call withAuthenticator, or withoutAuthentication to serve"
            + " the loopback host 127.0.0.1 unauthenticated",
        ex.getMessage());
  }

  @Test
  void withoutAuthenticationOnANonLoopbackHostIsRefusedNamingTheHost() {
    var builder =
        RuntimeServer.builder()
            .withRegistry(SessionRegistry.inMemory())
            .withOptionsFactory(factory())
            .withHost("0.0.0.0")
            .withoutAuthentication();
    var ex = assertThrows(IllegalStateException.class, builder::build);
    assertEquals(nonLoopbackRefusal("0.0.0.0"), ex.getMessage());
  }

  @Test
  void aNonLoopbackHostWithNoAuthenticatorIsRefusedNamingTheHost() {
    var builder =
        RuntimeServer.builder()
            .withRegistry(SessionRegistry.inMemory())
            .withOptionsFactory(factory())
            .withHost("0.0.0.0");
    var ex = assertThrows(IllegalStateException.class, builder::build);
    assertEquals(nonLoopbackRefusal("0.0.0.0"), ex.getMessage());
  }

  @Test
  void anAuthenticatorChosenAfterWithoutAuthenticationReplacesIt() {
    var server =
        RuntimeServer.builder()
            .withRegistry(SessionRegistry.inMemory())
            .withOptionsFactory(factory())
            .withHost("0.0.0.0")
            .withoutAuthentication()
            .withAuthenticator(new TestAuthenticator(request -> Optional.empty()))
            .build();
    try (server) {
      assertTrue(server.port() > 0);
    }
  }

  @Test
  void anAuthenticatorWithAnInvalidChallengeIsRefused() {
    var builder =
        RuntimeServer.builder()
            .withRegistry(SessionRegistry.inMemory())
            .withOptionsFactory(factory())
            .withAuthenticator(
                new TestAuthenticator(request -> Optional.empty(), "Bearer realm=\"a\"\r\n"));

    var ex = assertThrows(IllegalArgumentException.class, builder::build);

    assertEquals(
        "challenge must be printable ASCII (0x20-0x7E), got 0x000D at index 16", ex.getMessage());
  }

  @Test
  void withoutAuthenticationIsAcceptedForTheLoopbackHostName() {
    var server =
        RuntimeServer.builder()
            .withRegistry(SessionRegistry.inMemory())
            .withOptionsFactory(factory())
            .withHost("localhost")
            .withoutAuthentication()
            .build();
    try (server) {
      assertTrue(server.port() > 0);
    }
  }

  @Test
  void anUnresolvableHostIsRefused() {
    var builder =
        RuntimeServer.builder()
            .withRegistry(SessionRegistry.inMemory())
            .withOptionsFactory(factory())
            .withHost("helios-runtime-test.invalid")
            .withoutAuthentication();
    var ex = assertThrows(IllegalArgumentException.class, builder::build);
    assertEquals("host helios-runtime-test.invalid cannot be resolved", ex.getMessage());
  }

  @Test
  void theLoopbackHostNameBindsALoopbackAddress() {
    var server =
        RuntimeServer.builder()
            .withRegistry(SessionRegistry.inMemory())
            .withOptionsFactory(factory())
            .withHost("localhost")
            .withoutAuthentication()
            .build();
    try (server) {
      assertTrue(server.configForTests().address().isLoopbackAddress());
    }
  }

  @Test
  void theServerServesAtMostFiveHundredTwelveRequestsAtOnce() {
    var server =
        RuntimeServer.builder()
            .withRegistry(SessionRegistry.inMemory())
            .withOptionsFactory(factory())
            .withoutAuthentication()
            .build();
    try (server) {
      assertEquals(512, server.configForTests().maxConcurrentRequests());
    }
  }

  private static String nonLoopbackRefusal(String host) {
    return "host "
        + host
        + " ("
        + host
        + ") is not a loopback address and no authenticator is set; a server reachable from other"
        + " hosts requires withAuthenticator(...), for example a TrustedProxyAuthenticator";
  }

  @Test
  void noHelidonFeatureFoundOnTheClasspathIsInstalled() {
    var server =
        RuntimeServer.builder()
            .withRegistry(SessionRegistry.inMemory())
            .withOptionsFactory(factory())
            .withoutAuthentication()
            .build();
    try (server) {
      assertEquals(List.of(), server.configForTests().features());
    }
  }

  // ── lifecycle ────────────────────────────────────────────────────────────

  @Test
  void startAndCloseCleanly() {
    var registry = SessionRegistry.inMemory();
    var server =
        RuntimeServer.builder()
            .withRegistry(registry)
            .withOptionsFactory(factory())
            .withPort(0)
            .withoutAuthentication()
            .withHost("127.0.0.1")
            .build();
    try {
      assertTrue(server.port() > 0);
    } finally {
      server.close();
    }
  }

  @Test
  void closeIsIdempotent() {
    var server =
        RuntimeServer.builder()
            .withRegistry(SessionRegistry.inMemory())
            .withOptionsFactory(factory())
            .withPort(0)
            .withoutAuthentication()
            .withHost("127.0.0.1")
            .build();
    server.close();
    server.close();
  }

  @Test
  void buildWithCustomObjectMapperHonors() {
    var customMapper = JsonMapper.builder().build();
    var server =
        RuntimeServer.builder()
            .withRegistry(SessionRegistry.inMemory())
            .withOptionsFactory(factory())
            .withObjectMapper(customMapper)
            .withPort(0)
            .withoutAuthentication()
            .withHost("127.0.0.1")
            .build();
    try {
      assertNotNull(server);
      assertTrue(server.port() > 0);
    } finally {
      server.close();
    }
  }

  @Test
  void defaultHostIsLoopbackNotAllInterfaces() throws Exception {
    assertEquals("127.0.0.1", RuntimeServer.Builder.defaultHostForTests());
    var server =
        RuntimeServer.builder()
            .withRegistry(SessionRegistry.inMemory())
            .withOptionsFactory(factory())
            .withPort(0)
            .withoutAuthentication()
            .build();
    try (server;
        var socket = new Socket()) {
      socket.connect(new InetSocketAddress("127.0.0.1", server.port()));
      assertTrue(socket.isConnected());
    }
  }

  @Test
  void buildWithCustomRoutePrefixHonors() {
    var server =
        RuntimeServer.builder()
            .withRegistry(SessionRegistry.inMemory())
            .withOptionsFactory(factory())
            .withRoutePrefix("/api")
            .withPort(0)
            .withoutAuthentication()
            .withHost("127.0.0.1")
            .build();
    try {
      assertTrue(server.port() > 0);
    } finally {
      server.close();
    }
  }
}
