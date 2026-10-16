/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import com.standardapplied.helios.core.test.Await;
import io.helidon.http.HeaderNames;
import io.helidon.webserver.http.ServerRequest;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authenticates a bearer token {@code <name>} as subject {@code <name>} of tenant {@code
 * tenant-<name>} until {@link #revoke} revokes it; the next request with that token is
 * authenticated afresh.
 */
final class RevocableTokens implements Authenticator {

  private final Map<String, CompletableFuture<Void>> credentials = new ConcurrentHashMap<>();

  @Override
  public Optional<Authentication> authenticate(ServerRequest request) {
    return request
        .headers()
        .first(HeaderNames.AUTHORIZATION)
        .map(header -> header.substring("Bearer ".length()))
        .map(
            name ->
                new Authentication(
                    new Principal("tenant-" + name, name),
                    Await.HANG_GUARD,
                    credentials.computeIfAbsent(name, token -> new CompletableFuture<>())));
  }

  @Override
  public String challenge() {
    return TestAuthenticator.CHALLENGE;
  }

  /** Revoke the identity every request so far authenticated with {@code token} acts as. */
  void revoke(String token) {
    credentials.remove(token).complete(null);
  }
}
