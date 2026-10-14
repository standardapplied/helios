/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import com.standardapplied.helios.core.common.Strings;
import io.helidon.http.HeaderName;
import io.helidon.http.HeaderNames;
import io.helidon.webserver.http.ServerRequest;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The proxy-boundary {@link Authenticator}: accepts the identity an authenticating reverse proxy
 * asserts in two request headers, and only from a connection whose peer is one of the configured
 * proxy addresses. A request from any other peer is unauthenticated whatever headers it carries, so
 * a client that reaches the runtime directly cannot impersonate a user.
 *
 * <p>The proxy must authenticate every request, remove any client-supplied copy of both headers and
 * set each exactly once. A missing, repeated or invalid ({@link Principal}) header is
 * unauthenticated. The identity is re-asserted on every request, so it carries no lifetime of its
 * own ({@link Authentication#of(Principal)}); a long-lived stream lasts until the proxy closes the
 * connection.
 *
 * <pre>{@code
 * var authenticator = TrustedProxyAuthenticator.newBuilder()
 *     .withTrustedProxy(InetAddress.getByName("10.0.0.5"))
 *     .build();
 * }</pre>
 */
public final class TrustedProxyAuthenticator implements Authenticator {

  /** Default header carrying the authenticated subject. */
  public static final String DEFAULT_SUBJECT_HEADER = "X-Authenticated-Subject";

  /** Default header carrying the subject's tenant. */
  public static final String DEFAULT_TENANT_HEADER = "X-Authenticated-Tenant";

  private final Set<InetAddress> trustedProxies;
  private final HeaderName subjectHeader;
  private final HeaderName tenantHeader;

  private TrustedProxyAuthenticator(Builder builder) {
    this.trustedProxies = Set.copyOf(builder.trustedProxies);
    this.subjectHeader = HeaderNames.create(builder.subjectHeader);
    this.tenantHeader = HeaderNames.create(builder.tenantHeader);
  }

  /**
   * Start building an authenticator.
   *
   * @return a fresh builder
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  @Override
  public Optional<Authentication> authenticate(ServerRequest request) {
    if (!(request.remotePeer().address() instanceof InetSocketAddress peer)
        || !trustedProxies.contains(peer.getAddress())) {
      return Optional.empty();
    }
    var tenant = single(request, tenantHeader);
    var subject = single(request, subjectHeader);
    if (tenant.isEmpty() || subject.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(Authentication.of(new Principal(tenant.get(), subject.get())));
  }

  private static Optional<String> single(ServerRequest request, HeaderName name) {
    var values = request.headers().all(name, List::of);
    return values.size() == 1 && Principal.isValidPart(values.getFirst())
        ? Optional.of(values.getFirst())
        : Optional.empty();
  }

  /** Fluent builder for {@link TrustedProxyAuthenticator}. */
  public static final class Builder {

    private final Set<InetAddress> trustedProxies = new HashSet<>();
    private String subjectHeader = DEFAULT_SUBJECT_HEADER;
    private String tenantHeader = DEFAULT_TENANT_HEADER;

    private Builder() {}

    /**
     * Trust identity headers on connections from {@code proxy}. Call once per proxy address.
     *
     * @param proxy the proxy's address as the runtime sees the connection's peer; non-null
     * @return this builder
     * @throws NullPointerException if {@code proxy} is null
     */
    public Builder withTrustedProxy(InetAddress proxy) {
      trustedProxies.add(Objects.requireNonNull(proxy, "proxy must not be null"));
      return this;
    }

    /**
     * Read the subject from {@code header} instead of {@value #DEFAULT_SUBJECT_HEADER}.
     *
     * @param header the header name; non-blank
     * @return this builder
     * @throws IllegalArgumentException if {@code header} is null or blank
     */
    public Builder withSubjectHeader(String header) {
      this.subjectHeader = requireHeader("subject", header);
      return this;
    }

    /**
     * Read the tenant from {@code header} instead of {@value #DEFAULT_TENANT_HEADER}.
     *
     * @param header the header name; non-blank
     * @return this builder
     * @throws IllegalArgumentException if {@code header} is null or blank
     */
    public Builder withTenantHeader(String header) {
      this.tenantHeader = requireHeader("tenant", header);
      return this;
    }

    /**
     * Build the authenticator.
     *
     * @return the authenticator
     * @throws IllegalStateException if no trusted proxy was added, or both parts are read from the
     *     same header
     */
    public TrustedProxyAuthenticator build() {
      if (trustedProxies.isEmpty()) {
        throw new IllegalStateException(
            "at least one trusted proxy is required — call withTrustedProxy before build");
      }
      if (subjectHeader.equalsIgnoreCase(tenantHeader)) {
        throw new IllegalStateException(
            "subject and tenant must come from different headers; both are " + subjectHeader);
      }
      return new TrustedProxyAuthenticator(this);
    }

    private static String requireHeader(String part, String header) {
      if (Strings.isBlank(header)) {
        throw new IllegalArgumentException(part + " header must be non-blank");
      }
      return header;
    }
  }
}
