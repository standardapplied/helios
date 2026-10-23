/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.runtime;

import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.session.SessionOptions;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.WebServerConfig;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.http.ServerRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
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
 *     .withOptionsFactory(request -> SessionOptions.newBuilder()
 *         .withModel(myModel)
 *         .withSessionId(request.sessionId())
 *         .withSystemPrompt(promptFor(request.owner().tenant()))
 *         .build())
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
 * <p>Requests are bounded per principal by the {@link RequestLimits} set through {@link
 * Builder#withRequestLimits(RequestLimits)} (default {@link RequestLimits#defaults()}), and across
 * principals to 512 in flight: Helidon answers one more {@code 503}, with its own body and no
 * {@code Retry-After}, and closes its connection. Sessions are bounded by the registry's caps. The
 * server calls {@link SessionRegistry#sweep()} every 30 seconds on a virtual thread of its own, so
 * sessions not running past their retention are closed without a deployer's scheduler.
 *
 * <p>The server installs the {@link UnreadBodyGuard}: any answer, the service's or Helidon's own,
 * sent while the request's body is unread is written whole and then ends the connection, without
 * reading further, so no byte sent after it is drained or parsed as a request. A request whose body
 * was read to its end, or that has none, keeps its connection.
 *
 * <p>The server installs no Helidon feature discovered on the classpath, since a discovered feature
 * could mount routes the {@link Authenticator} never sees. To combine the runtime with Helidon
 * features, mount {@link AgentHttpService} on a {@link WebServer} of your own.
 *
 * <h2>Thread-safety</h2>
 *
 * The wrapped {@link WebServer} is thread-safe. {@link #close()} is idempotent.
 */
public final class RuntimeServer implements AutoCloseable {

  private static final Logger LOGGER = Logger.getLogger(RuntimeServer.class.getName());

  private static final int MAX_CONCURRENT_REQUESTS = 512;

  private final WebServer webServer;
  private final SessionRegistry registry;
  private final ScheduledExecutorService sweeper;

  private RuntimeServer(
      WebServer webServer, SessionRegistry registry, ScheduledExecutorService sweeper) {
    this.webServer = webServer;
    this.registry = registry;
    this.sweeper = sweeper;
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

  /** The configuration the server started with. Visible for tests. */
  WebServerConfig configForTests() {
    return webServer.prototype();
  }

  /**
   * Stop sweeping, stop the server, and close every registered session. Idempotent. A sweep under
   * way is not interrupted: this waits for it to finish closing its sessions, so no sweep runs once
   * this returns.
   */
  @Override
  public void close() {
    sweeper.close();
    webServer.stop();
    registry.closeAll();
  }

  /** Sweep {@code registry}, logging a failure so the next scheduled sweep still runs. */
  static void sweep(SessionRegistry registry) {
    try {
      registry.sweep();
    } catch (RuntimeException e) {
      LOGGER.log(Level.WARNING, "session sweep failed", e);
    }
  }

  /** Mutable builder for {@link RuntimeServer}. */
  public static final class Builder {

    private static final Principal LOCAL_PRINCIPAL = new Principal("local", "local");

    /**
     * Never denies, so its challenge is never sent; it exists because every authenticator has one.
     */
    private static final Authenticator LOCAL =
        new Authenticator() {
          @Override
          public Optional<Authentication> authenticate(ServerRequest request) {
            return Optional.of(Authentication.of(LOCAL_PRINCIPAL));
          }

          @Override
          public String challenge() {
            return "Local realm=\"helios\"";
          }
        };

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
    private Function<SessionRequest, SessionOptions> optionsFactory;
    private ObjectMapper objectMapper;
    private String routePrefix = "/v1";
    private int port = 0;
    private String host = DEFAULT_HOST;
    private Authenticator authenticator;
    private RequestLimits requestLimits = RequestLimits.defaults();
    private Duration sweepInterval = Duration.ofSeconds(30);

    private Builder() {}

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
     * Bind the options factory the service calls for every {@code POST /sessions} request, with the
     * generated session id and the principal that will own the session.
     *
     * @param optionsFactory non-null function from a {@link SessionRequest} to options for its
     *     session id
     * @return this builder
     * @throws NullPointerException if {@code optionsFactory} is null
     */
    public Builder withOptionsFactory(Function<SessionRequest, SessionOptions> optionsFactory) {
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
     * Bound every request with {@code requestLimits}. Default {@link RequestLimits#defaults()}.
     *
     * @param requestLimits non-null limits
     * @return this builder
     * @throws NullPointerException if {@code requestLimits} is null
     */
    public Builder withRequestLimits(RequestLimits requestLimits) {
      this.requestLimits = Objects.requireNonNull(requestLimits, "requestLimits must not be null");
      return this;
    }

    /** Sweep the registry every {@code sweepInterval}, at least one millisecond; for tests. */
    Builder withSweepInterval(Duration sweepInterval) {
      Objects.requireNonNull(sweepInterval, "sweepInterval must not be null");
      if (sweepInterval.toMillis() < 1) {
        throw new IllegalArgumentException(
            "sweepInterval must be at least 1 ms, got " + sweepInterval);
      }
      this.sweepInterval = sweepInterval;
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
     * @throws IllegalArgumentException if the host cannot be resolved, or the authenticator's
     *     {@link Authenticator#challenge()} is not a valid RFC 9110 challenge
     * @throws NullPointerException if the authenticator's challenge is null
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
          new AgentHttpService(
              registry, optionsFactory, authenticator, mapper, routePrefix, requestLimits);
      var jacksonSupport = JacksonSupport.create(mapper);
      var server =
          WebServer.builder()
              .address(bindAddress)
              .port(port)
              .maxConcurrentRequests(MAX_CONCURRENT_REQUESTS)
              .featuresDiscoverServices(false)
              .mediaContext(
                  mc -> mc.mediaSupportsDiscoverServices(false).addMediaSupport(jacksonSupport))
              .routing(
                  (HttpRouting.Builder routing) ->
                      routing.addFeature(new UnreadBodyGuard()).register(routePrefix, service))
              .build()
              .start();
      return new RuntimeServer(server, registry, startSweeper(registry, sweepInterval));
    }

    /**
     * Sweep {@code registry} every {@code interval}. Static, so the task holds what this server was
     * built with and never this builder, whose later changes must not reach a running server.
     */
    private static ScheduledExecutorService startSweeper(
        SessionRegistry registry, Duration interval) {
      var sweeper =
          Executors.newSingleThreadScheduledExecutor(
              Thread.ofVirtual().name("helios-session-sweep").factory());
      var period = interval.toMillis();
      sweeper.scheduleWithFixedDelay(() -> sweep(registry), period, period, TimeUnit.MILLISECONDS);
      return sweeper;
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
        return InetAddress.getByName(host);
      } catch (UnknownHostException e) {
        throw new IllegalArgumentException("host " + host + " cannot be resolved", e);
      }
    }
  }
}
