/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * What an {@link Authenticator} verified about one request: who it acts as and how long that stays
 * true.
 *
 * <p>The identity holds for {@code lifetime} from verification, or until {@code revoked} completes,
 * whichever comes first. A request whose identity has already ended is answered {@code 401}. A
 * long-lived request ends when its identity does: an SSE event stream closes without a further
 * event, and a result long poll answers {@code 401}. A request watches {@code revoked} only while
 * it runs, so one future may be shared by every authentication of a credential and need never
 * complete. Share that one future: deriving a future per request ({@code copy()}, {@code
 * thenApply(...)}, or {@code toCompletableFuture()} of a minimal stage) attaches a callback to the
 * source that outlives the request until the credential is revoked.
 *
 * @param principal the verified identity
 * @param lifetime how long the identity stays valid from now; zero or negative means it has expired
 * @param revoked completes, normally or exceptionally, when the credential is revoked
 */
public record Authentication(
    Principal principal, Duration lifetime, CompletableFuture<Void> revoked) {

  /**
   * Validate the components.
   *
   * @throws NullPointerException if any component is null
   */
  public Authentication {
    Objects.requireNonNull(principal, "principal must not be null");
    Objects.requireNonNull(lifetime, "lifetime must not be null");
    Objects.requireNonNull(revoked, "revoked must not be null");
  }

  /**
   * An identity that neither expires nor can be revoked through the runtime, for a verifier whose
   * credential is re-checked on every request, such as a trusted proxy's.
   *
   * @param principal the verified identity; non-null
   * @return the authentication
   */
  public static Authentication of(Principal principal) {
    return new Authentication(
        principal, ChronoUnit.FOREVER.getDuration(), new CompletableFuture<>());
  }
}
