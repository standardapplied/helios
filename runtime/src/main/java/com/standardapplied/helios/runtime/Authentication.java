/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * What an {@link Authenticator} verified about one request: who it acts as and how long that stays
 * true.
 *
 * <p>The identity holds for {@code lifetime} from verification, or until {@code revoked} completes,
 * whichever comes first. A request whose identity has already ended is answered {@code 401}. A
 * long-lived request ends when its identity does: an SSE event stream closes without a further
 * event, and a result long poll answers {@code 401}. A request watches {@code revoked} only while
 * it runs, so one stage may be shared by any number of requests and need never complete.
 *
 * @param principal the verified identity
 * @param lifetime how long the identity stays valid from now; zero or negative means it has expired
 * @param revoked completes, normally or exceptionally, when the credential is revoked
 */
public record Authentication(
    Principal principal, Duration lifetime, CompletionStage<Void> revoked) {

  /**
   * Validate the components.
   *
   * <p>{@code revoked} is held as its {@link CompletionStage#toCompletableFuture()} view, taken
   * once here so that requests sharing this authentication share one stage.
   *
   * @throws NullPointerException if any component is null
   * @throws UnsupportedOperationException if {@code revoked} has no {@code CompletableFuture} view
   */
  public Authentication {
    Objects.requireNonNull(principal, "principal must not be null");
    Objects.requireNonNull(lifetime, "lifetime must not be null");
    revoked = Objects.requireNonNull(revoked, "revoked must not be null").toCompletableFuture();
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
