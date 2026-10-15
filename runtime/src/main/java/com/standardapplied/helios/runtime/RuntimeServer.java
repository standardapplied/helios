/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.runtime;

import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.session.SessionOptions;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpRouting;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Programmatic Helidon SE {@link WebServer} wrapping an {@link AgentHttpService}. Designed for
 * embedding in a service main, or in tests that want a real HTTP endpoint on a random port.
 *
 * <p>Typical use:
 *
 * <pre>{@code
 * try (var server = RuntimeServer.builder()
 *     .withRegistry(SessionRegistry.inMemory())
 *     .withOptionsFactory(sessionId -> SessionOptions.newBuilder()
 *         .withModel(myModel).withSessionId(sessionId).build())
 *     .withHost("0.0.0.0")
 *     .withPort(8080)
 *     .withAuthenticator(TrustedProxyAuthenticator.newBuilder()
 *         .withTrustedProxy(InetAddress.getByName("10.0.0.5"))
 *         .build())
 *     .build()) {
 *   // serve until shutdown
 * }
 * }</pre>
 *
 * <p>Every request is authenticated by the configured {@link Authenticator}. A server bound to a
 * loopback address may instead be built {@link Builder#withoutAuthentication()}, for local tools
 * and tests: every request then acts as one local principal. A server bound to any other address
 * has no unauthenticated mode; a deployer behind an authenticating proxy configures {@link
 * TrustedProxyAuthenticator}.
 *
 * <h2>Thread-safety</h2>
 *
 * The wrapped {@link WebServer} is thread-safe. {@link #close()} is idempotent.
 */
public final class RuntimeServer implements AutoCloseable {

  private final WebServer webServer;
  private final SessionRegistry registry;

  private RuntimeServer(WebServer webServer, SessionRegistry registry) {
    this.webServer = webServer;
    this.registry = registry;
  }

  /**
   * Start building a server.
   *
   * @return a fresh builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * The port the server is bound to. Useful when constructed with port 0 (auto-assigned).
   *
   * @return the bound port
   */
  public int port() {
    return webServer.port();
  }

  /**
   * The registry of sessions the server is fronting.
   *
   * @return non-null registry
   */
  public SessionRegistry registry() {
    return registry;
  }

  /** Stop the server and close every registered session. Idempotent. */
  @Override
  public void close() {
    webServer.stop();
    registry.closeAll();
  }

  /** Mutable builder for {@link RuntimeServer}. */
  public static final class Builder {

    private static final Principal LOCAL_PRINCIPAL = new Principal("local", "local");

    private static final Authenticator LOCAL =
        request -> Optional.of(Authentication.of(LOCAL_PRINCIPAL));

    /**
     * Fail-secure default: loopback only, so a server is reachable from other hosts only when the
     * deployer names an external interface through {@link #withHost(String)}.
     */
    static final String DEFAULT_HOST = "127.0.0.1";

    /** Visible for tests so the default value can be asserted without reflection. */
    static String defaultHostForTests() {
      return DEFAULT_HOST;
    }

    private SessionRegistry registry;
    private Function<String, SessionOptions> optionsFactory;
    private ObjectMapper objectMapper;
    private String routePrefix = "/v1";
    private int port = 0;
    private String host = DEFAULT_HOST;
    private Authenticator authenticator;
    private HostResolver resolver = InetAddress::getByName;

    private Builder() {}

    /** Resolve the host through {@code resolver} instead of the JDK, for tests. */
    Builder withResolverForTests(HostResolver resolver) {
      this.resolver = resolver;
      return this;
    }

    /**
     * Authenticate every request with {@code authenticator}.
     *
     * @param authenticator non-null authenticator
     * @return this builder
     * @throws NullPointerException if {@code authenticator} is null
     */
    public Builder withAuthenticator(Authenticator authenticator) {
      this.authenticator = Objects.requireNonNull(authenticator, "authenticator must not be null");
      return this;
    }

    /**
     * Serve every request unauthenticated, as one local principal. Accepted only for a loopback
     * host; {@link #build()} refuses it for any other.
     *
     * @return this builder
     */
    public Builder withoutAuthentication() {
      this.authenticator = LOCAL;
      return this;
    }

    /**
     * Bind the registry the service exposes.
     *
     * @param registry non-null registry
     * @return this builder
     * @throws NullPointerException if {@code registry} is null
     */
    public Builder withRegistry(SessionRegistry registry) {
      this.registry = Objects.requireNonNull(registry, "registry must not be null");
      return this;
    }

    /**
     * Bind the options factory the service calls for every {@code POST /sessions} request.
     *
     * @param optionsFactory non-null function from session id to options
     * @return this builder
     * @throws NullPointerException if {@code optionsFactory} is null
     */
    public Builder withOptionsFactory(Function<String, SessionOptions> optionsFactory) {
      this.optionsFactory =
          Objects.requireNonNull(optionsFactory, "optionsFactory must not be null");
      return this;
    }

    /**
     * Override the default {@link JsonMapper}. Most callers should leave this alone.
     *
     * @param objectMapper non-null mapper
     * @return this builder
     */
    public Builder withObjectMapper(ObjectMapper objectMapper) {
      this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
      return this;
    }

    /**
     * Override the URL prefix the service is mounted under (default {@code /v1}).
     *
     * @param routePrefix non-blank prefix; leading slash required
     * @return this builder
     */
    public Builder withRoutePrefix(String routePrefix) {
      Objects.requireNonNull(routePrefix, "routePrefix must not be null");
      if (Strings.isBlank(routePrefix) || !routePrefix.startsWith("/")) {
        throw new IllegalArgumentException(
            "routePrefix must be non-blank and start with '/'; got '" + routePrefix + "'");
      }
      this.routePrefix = routePrefix;
      return this;
    }

    /**
     * Bind the HTTP listen port. Default {@code 0} (kernel-assigned). Tests should leave this at
     * the default; production deployments typically pass an explicit port via configuration.
     *
     * @param port non-negative port (0 = random)
     * @return this builder
     */
    public Builder withPort(int port) {
      if (port < 0 || port > 65535) {
        throw new IllegalArgumentException("port must be in [0, 65535], got " + port);
      }
      this.port = port;
      return this;
    }

    /**
     * Bind the HTTP listen host. Default {@code 127.0.0.1} (loopback only — fail-secure). Pass
     * {@code "0.0.0.0"} or a specific external interface to expose the service beyond the host,
     * which requires {@link #withAuthenticator(Authenticator)}.
     *
     * @param host non-blank host
     * @return this builder
     */
    public Builder withHost(String host) {
      Objects.requireNonNull(host, "host must not be null");
      if (Strings.isBlank(host)) {
        throw new IllegalArgumentException("host must not be blank");
      }
      this.host = host;
      return this;
    }

    /**
     * Build and start the server.
     *
     * @return the started server
     * @throws IllegalStateException if {@code registry} or {@code optionsFactory} was never set,
     *     neither an authenticator nor {@link #withoutAuthentication()} was chosen, or the host is
     *     not a loopback address and no authenticator was set
     * @throws IllegalArgumentException if the host cannot be resolved
     */
    public RuntimeServer build() {
      if (registry == null) {
        throw new IllegalStateException("registry is required — call withRegistry before build");
      }
      if (optionsFactory == null) {
        throw new IllegalStateException(
            "optionsFactory is required — call withOptionsFactory before build");
      }
      var bindAddress = resolveHost();
      requireAuthenticationFits(bindAddress);
      var mapper = objectMapper != null ? objectMapper : JsonMapper.builder().build();
      var service =
          new AgentHttpService(registry, optionsFactory, authenticator, mapper, routePrefix);
      var jacksonSupport = JacksonSupport.create(mapper);
      var server =
          WebServer.builder()
              .address(bindAddress)
              .port(port)
              .mediaContext(
                  mc -> mc.mediaSupportsDiscoverServices(false).addMediaSupport(jacksonSupport))
              .routing((HttpRouting.Builder routing) -> routing.register(routePrefix, service))
              .build()
              .start();
      return new RuntimeServer(server, registry);
    }

    private void requireAuthenticationFits(InetAddress bindAddress) {
      if ((authenticator == null || authenticator == LOCAL) && !bindAddress.isLoopbackAddress()) {
        throw new IllegalStateException(
            "host "
                + host
                + " ("
                + bindAddress.getHostAddress()
                + ") is not a loopback address and no authenticator is set; a server reachable"
                + " from other hosts requires withAuthenticator(...), for example a"
                + " TrustedProxyAuthenticator");
      }
      if (authenticator == null) {
        throw new IllegalStateException(
            "an authenticator is required — call withAuthenticator, or withoutAuthentication to"
                + " serve the loopback host "
                + host
                + " unauthenticated");
      }
    }

    /**
     * Resolve {@code host} once; the server binds this address, so a later DNS answer cannot move
     * it off the address the loopback check approved.
     */
    private InetAddress resolveHost() {
      try {
        return resolver.resolve(host);
      } catch (UnknownHostException e) {
        throw new IllegalArgumentException("host " + host + " cannot be resolved", e);
      }
    }
  }

  /** Resolves a host name to the one address the server binds. */
  @FunctionalInterface
  interface HostResolver {
    InetAddress resolve(String host) throws UnknownHostException;
  }
}
