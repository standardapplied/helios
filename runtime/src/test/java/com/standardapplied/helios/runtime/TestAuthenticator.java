/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import io.helidon.webserver.http.ServerRequest;
import java.util.Optional;
import java.util.function.Function;

/**
 * An {@link Authenticator} that verifies through {@code verifier} and challenges with {@code
 * challenge}, by default {@value #CHALLENGE}, so a test asserts the challenge every {@code 401}
 * carries.
 *
 * @param verifier decides each request's identity
 * @param challenge the authenticator's challenge, valid or not
 */
record TestAuthenticator(
    Function<ServerRequest, Optional<Authentication>> verifier, String challenge)
    implements Authenticator {

  /** The challenge of a test authenticator unless a test names another. */
  static final String CHALLENGE = "Bearer realm=\"test\"";

  TestAuthenticator(Function<ServerRequest, Optional<Authentication>> verifier) {
    this(verifier, CHALLENGE);
  }

  @Override
  public Optional<Authentication> authenticate(ServerRequest request) {
    return verifier.apply(request);
  }
}
