/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.tool.Tool;
import ai.singlr.session.SessionOptions;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

final class RuntimeServerTest {

  private static Model stubModel() {
    return new Model() {
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

  private static java.util.function.Function<String, SessionOptions> factory() {
    return sessionId ->
        SessionOptions.newBuilder().withModel(stubModel()).withSessionId(sessionId).build();
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

  // ── lifecycle ────────────────────────────────────────────────────────────

  @Test
  void startAndCloseCleanly() {
    var registry = SessionRegistry.inMemory();
    var server =
        RuntimeServer.builder()
            .withRegistry(registry)
            .withOptionsFactory(factory())
            .withPort(0)
            .withHost("127.0.0.1")
            .build();
    try {
      assertTrue(server.port() > 0);
      assertSame(registry, server.registry());
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
  void defaultHostIsLoopbackNotAllInterfaces() {
    // Fail-secure default: a deployer who forgets withHost(...) should NOT have their service
    // exposed on every interface. POST /sessions is unauthenticated and spends real money on the
    // configured model. Loopback is the conservative default; explicit withHost("0.0.0.0") is
    // the opt-in for all-interfaces binding.
    assertEquals("127.0.0.1", RuntimeServer.Builder.defaultHostForTests());
    var server =
        RuntimeServer.builder()
            .withRegistry(SessionRegistry.inMemory())
            .withOptionsFactory(factory())
            .withPort(0)
            .build();
    try {
      assertTimeoutPreemptively(
          java.time.Duration.ofSeconds(2),
          () -> {
            try (var s = new java.net.Socket()) {
              s.connect(new java.net.InetSocketAddress("127.0.0.1", server.port()), 1000);
              assertTrue(s.isConnected());
            }
          });
    } finally {
      server.close();
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
            .withHost("127.0.0.1")
            .build();
    try {
      assertTrue(server.port() > 0);
    } finally {
      server.close();
    }
  }
}
