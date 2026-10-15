/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import io.helidon.webserver.http.ServerRequest;
import java.util.Optional;

/**
 * Verifies the identity of one HTTP request against a trusted source: a token verifier, a mutual
 * TLS certificate check, or a trusted proxy's assertion ({@link TrustedProxyAuthenticator}). The
 * runtime calls it before it reads the request's body or looks up any session, on every route.
 *
 * <p>An implementation never takes identity from a header an arbitrary client can set, and never
 * holds a credential of its own. It returns empty for a missing, malformed, expired, revoked or
 * forged credential; the runtime answers each of them with the same {@code 401}. It throws only
 * when it cannot decide, for example when its key source is unreachable; the runtime answers {@code
 * 503} and logs the exception's type, never its message, since a message may quote the credential.
 */
@FunctionalInterface
public interface Authenticator {

  /**
   * Verify {@code request}'s credential.
   *
   * @param request the request to verify; non-null
   * @return the verified identity, or empty when the request carries no valid credential
   */
  Optional<Authentication> authenticate(ServerRequest request);
}
