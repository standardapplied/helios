/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import io.helidon.http.HeaderNames;
import io.helidon.http.Status;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * The one place a request's identity is decided: runs the deployer's {@link Authenticator} and
 * either admits the request as a {@link Caller} or answers it, {@code 401} for no valid identity
 * and {@code 503} when the authenticator fails. Neither answer says why, so a denial discloses
 * nothing about the credential. Every {@code 401} the runtime sends is built here, with the
 * authenticator's challenge in {@code WWW-Authenticate}.
 */
final class AuthenticationGate {

  /** The HTTP surface's logger, so one logging configuration covers every route. */
  private static final Logger LOGGER = Logger.getLogger(AgentHttpService.class.getName());

  /** The body of every {@code 401}, whatever the reason, so a denial says nothing about it. */
  private static final Map<String, String> UNAUTHENTICATED = Map.of("error", "unauthenticated");

  private final Authenticator authenticator;
  private final String challenge;

  /**
   * Build the gate for {@code authenticator}, reading its challenge once.
   *
   * @throws NullPointerException if {@code authenticator} or its challenge is null
   * @throws IllegalArgumentException if its challenge is not a valid {@link Challenge}
   */
  AuthenticationGate(Authenticator authenticator) {
    this.authenticator = Objects.requireNonNull(authenticator, "authenticator must not be null");
    this.challenge = Challenge.requireValid(authenticator.challenge());
  }

  /**
   * Admit {@code req} or answer it.
   *
   * @param req the request to authenticate
   * @param resp the response a denial is sent on
   * @return the caller when admitted; empty when a denial has been sent
   */
  Optional<Caller> admit(ServerRequest req, ServerResponse resp) {
    Optional<Authentication> authentication;
    try {
      authentication =
          Objects.requireNonNull(authenticator.authenticate(req), "authenticator returned null");
    } catch (RuntimeException e) {
      LOGGER.warning("authenticator failed with " + e.getClass().getName());
      resp.status(Status.SERVICE_UNAVAILABLE_503)
          .send(Map.of("error", "authentication unavailable"));
      return Optional.empty();
    }
    var caller =
        authentication.map(verified -> Caller.start(verified, challenge)).filter(Caller::valid);
    if (caller.isEmpty()) {
      unauthenticated(resp, challenge);
    }
    return caller;
  }

  /**
   * Answer {@code resp} {@code 401}: {@code challenge} in {@code WWW-Authenticate} and a body that
   * says nothing about why.
   */
  private static void unauthenticated(ServerResponse resp, String challenge) {
    resp.header(HeaderNames.WWW_AUTHENTICATE, challenge)
        .status(Status.UNAUTHORIZED_401)
        .send(UNAUTHENTICATED);
  }

  /**
   * An admitted request's identity for as long as the request runs.
   *
   * @param principal who the request acts as
   * @param ended completes when the identity's lifetime elapses, its credential is revoked, or the
   *     request ends ({@link #release()})
   * @param challenge the authenticator's challenge, which the {@code 401} for an ended identity
   *     carries
   */
  record Caller(Principal principal, CompletableFuture<Void> ended, String challenge) {

    static Caller start(Authentication authentication, String challenge) {
      var ended = new CompletableFuture<Void>();
      if (authentication.lifetime().isPositive()) {
        ended.completeOnTimeout(
            null, TimeUnit.MILLISECONDS.convert(authentication.lifetime()), TimeUnit.MILLISECONDS);
      } else {
        ended.complete(null);
      }
      CompletableFuture.anyOf(authentication.revoked(), ended)
          .whenComplete((ignored, failure) -> ended.complete(null));
      return new Caller(authentication.principal(), ended, challenge);
    }

    boolean valid() {
      return !ended.isDone();
    }

    /**
     * Answer {@code 401} if the identity ended while the request waited, on a slow body, the
     * options factory or a result long poll, so no work is queued and no result disclosed for an
     * expired or revoked identity.
     *
     * @return {@code true} when the identity ended and the request has been answered
     */
    boolean answeredAsEnded(ServerResponse resp) {
      if (valid()) {
        return false;
      }
      unauthenticated(resp, challenge);
      return true;
    }

    /** End the identity's watch once the request is answered, cancelling its expiry timer. */
    void release() {
      ended.complete(null);
    }
  }
}
