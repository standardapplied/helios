/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import io.helidon.http.HeaderNames;
import io.helidon.http.Status;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Pattern;

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

  /** Longest accepted challenge, in characters. */
  static final int MAX_CHALLENGE_LENGTH = 1024;

  private static final String TOKEN = "[!#$%&'*+.^_`|~0-9A-Za-z-]+";

  private static final String QUOTED_STRING = "\"(?:[ !#-\\[\\]-~]|\\\\[ -~])*\"";

  private static final String AUTH_PARAM =
      "(" + TOKEN + ") *= *(?:" + TOKEN + "|" + QUOTED_STRING + ")";

  private static final Pattern AUTH_SCHEME = Pattern.compile(TOKEN);

  private static final Pattern AUTH_PARAM_NAME = Pattern.compile(AUTH_PARAM);

  private static final Pattern TOKEN68_OR_AUTH_PARAMS =
      Pattern.compile("[0-9A-Za-z._~+/-]+=*|" + AUTH_PARAM + "(?: *, *" + AUTH_PARAM + ")*");

  private final Authenticator authenticator;
  private final String challenge;

  /**
   * Build the gate for {@code authenticator}, reading its challenge once.
   *
   * @throws NullPointerException if {@code authenticator} or its challenge is null
   * @throws IllegalArgumentException if its challenge is not a valid RFC 9110 challenge
   */
  AuthenticationGate(Authenticator authenticator) {
    this.authenticator = Objects.requireNonNull(authenticator, "authenticator must not be null");
    this.challenge = requireValidChallenge(authenticator.challenge());
  }

  /**
   * Return {@code challenge} if it is a valid RFC 9110 {@code challenge}: an auth-scheme token,
   * optionally followed by one space and a token68 or a comma-separated list of auth-params whose
   * names are unique ignoring case, in printable ASCII, non-blank and at most {@value
   * #MAX_CHALLENGE_LENGTH} characters.
   *
   * @param challenge the candidate {@code WWW-Authenticate} value
   * @return {@code challenge}
   * @throws NullPointerException if {@code challenge} is null
   * @throws IllegalArgumentException naming the rule {@code challenge} breaks
   */
  static String requireValidChallenge(String challenge) {
    Objects.requireNonNull(challenge, "challenge must not be null");
    if (challenge.isBlank()) {
      throw new IllegalArgumentException("challenge must be non-blank");
    }
    if (challenge.length() > MAX_CHALLENGE_LENGTH) {
      throw new IllegalArgumentException(
          "challenge must be at most "
              + MAX_CHALLENGE_LENGTH
              + " characters, got "
              + challenge.length());
    }
    requirePrintableAscii(challenge);
    requireChallengeSyntax(challenge);
    return challenge;
  }

  private static void requirePrintableAscii(String challenge) {
    for (var i = 0; i < challenge.length(); i++) {
      var c = challenge.charAt(i);
      if (c < 0x20 || c > 0x7E) {
        throw new IllegalArgumentException(
            String.format(
                "challenge must be printable ASCII (0x20-0x7E), got 0x%04X at index %d",
                (int) c, i));
      }
    }
  }

  private static void requireChallengeSyntax(String challenge) {
    var space = challenge.indexOf(' ');
    var scheme = space < 0 ? challenge : challenge.substring(0, space);
    if (!AUTH_SCHEME.matcher(scheme).matches()) {
      throw new IllegalArgumentException(
          "challenge must begin with an auth-scheme token, got '" + challenge + "'");
    }
    if (space < 0) {
      return;
    }
    var credentials = challenge.substring(space + 1);
    if (!TOKEN68_OR_AUTH_PARAMS.matcher(credentials).matches()) {
      throw new IllegalArgumentException(
          "challenge's auth-scheme must be followed by one space and a token68 or a"
              + " comma-separated list of auth-params, got '"
              + challenge
              + "'");
    }
    requireUniqueParameterNames(challenge, credentials);
  }

  /**
   * RFC 9110 §11.2: each auth-param name occurs once per challenge, compared case-insensitively.
   */
  private static void requireUniqueParameterNames(String challenge, String credentials) {
    var names = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
    var parameters = AUTH_PARAM_NAME.matcher(credentials);
    while (parameters.find()) {
      if (!names.add(parameters.group(1))) {
        throw new IllegalArgumentException(
            "challenge's auth-param names must be unique, ignoring case, but '"
                + parameters.group(1)
                + "' repeats in '"
                + challenge
                + "'");
      }
    }
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
    var caller = authentication.map(verified -> Caller.start(verified, this)).filter(Caller::valid);
    if (caller.isEmpty()) {
      unauthenticated(resp);
    }
    return caller;
  }

  /**
   * Answer {@code resp} {@code 401}: the authenticator's challenge in {@code WWW-Authenticate} and
   * a body that says nothing about why.
   *
   * @param resp the response to answer
   */
  void unauthenticated(ServerResponse resp) {
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
   * @param gate the gate that admitted the request, which answers it once the identity has ended
   */
  record Caller(Principal principal, CompletableFuture<Void> ended, AuthenticationGate gate) {

    static Caller start(Authentication authentication, AuthenticationGate gate) {
      var ended = new CompletableFuture<Void>();
      if (authentication.lifetime().isPositive()) {
        ended.completeOnTimeout(
            null, TimeUnit.MILLISECONDS.convert(authentication.lifetime()), TimeUnit.MILLISECONDS);
      } else {
        ended.complete(null);
      }
      CompletableFuture.anyOf(authentication.revoked(), ended)
          .whenComplete((ignored, failure) -> ended.complete(null));
      return new Caller(authentication.principal(), ended, gate);
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
      gate.unauthenticated(resp);
      return true;
    }

    /** End the identity's watch once the request is answered, cancelling its expiry timer. */
    void release() {
      ended.complete(null);
    }
  }
}
