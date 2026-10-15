/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import io.helidon.webserver.CloseConnectionException;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import java.lang.reflect.Proxy;
import java.util.function.Function;

/**
 * Stand-ins for Helidon's {@link ServerRequest} and {@link ServerResponse}, for driving a route's
 * work without a server. Each answers a call by the called method's name; a fluent call returns the
 * stub itself.
 */
final class ServerStubs {

  private ServerStubs() {}

  /** A response an admitted request never writes to before its work runs. */
  static ServerResponse untouchedResponse() {
    return stub(
        ServerResponse.class,
        name -> {
          throw new AssertionError("an admitted request was answered: " + name);
        });
  }

  /** A response whose client went away: the refusal's headers are set, sending it fails. */
  static ServerResponse disconnectedResponse() {
    return stub(
        ServerResponse.class,
        name -> {
          if ("send".equals(name)) {
            throw new CloseConnectionException("peer closed");
          }
          return null;
        });
  }

  /** A {@code type} whose calls are answered by {@code calls}, given the method's name. */
  static <T> T stub(Class<T> type, Function<String, Object> calls) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> {
              var answer = calls.apply(method.getName());
              return method.getReturnType().isInstance(proxy) ? proxy : answer;
            }));
  }
}
