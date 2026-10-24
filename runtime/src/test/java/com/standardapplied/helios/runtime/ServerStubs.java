/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import io.helidon.common.uri.UriQuery;
import io.helidon.http.Header;
import io.helidon.http.HeaderName;
import io.helidon.http.Status;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * Stand-ins for Helidon's {@link ServerRequest} and {@link ServerResponse}, for driving a route's
 * work without a server. Each answers a call by the called method's name and arguments; a fluent
 * call returns the stub itself.
 */
final class ServerStubs {

  private ServerStubs() {}

  /** A request whose query string is {@code query}, e.g. {@code timeout=0}. */
  static ServerRequest request(String query) {
    return stub(
        ServerRequest.class,
        (name, args) -> {
          if ("query".equals(name)) {
            return UriQuery.create(query);
          }
          throw new AssertionError("the request was asked for " + name);
        });
  }

  /** A {@code type} whose calls are answered by {@code calls}, given the method's name and args. */
  static <T> T stub(Class<T> type, BiFunction<String, Object[], Object> calls) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> {
              var answer = calls.apply(method.getName(), args == null ? new Object[0] : args);
              return method.getReturnType().isInstance(proxy) ? proxy : answer;
            }));
  }

  /** A response that records the status, headers and body it is answered with. */
  static final class RecordingResponse {

    private final Map<String, String> headers = new ConcurrentHashMap<>();
    private volatile Status status = Status.OK_200;
    private volatile byte[] body;

    final ServerResponse response = stub(ServerResponse.class, this::answer);

    Status status() {
      return status;
    }

    /** The value of the header named {@code name}, case-insensitively; null when unset. */
    String header(String name) {
      return headers.get(name.toLowerCase(Locale.ROOT));
    }

    /** The body as UTF-8; null until the response is sent. */
    String body() {
      return body == null ? null : new String(body, StandardCharsets.UTF_8);
    }

    boolean isSent() {
      return body != null;
    }

    private Object answer(String name, Object[] args) {
      switch (name) {
        case "status" -> status = (Status) args[0];
        case "header" -> record(args);
        case "send" -> send(args);
        case "isSent" -> {
          return isSent();
        }
        default -> throw new AssertionError("the response was asked for " + name);
      }
      return null;
    }

    private void record(Object[] args) {
      if (args[0] instanceof Header header) {
        headers.put(header.headerName().lowerCase(), header.get());
      } else {
        var values = (String[]) args[1];
        headers.put(((HeaderName) args[0]).lowerCase(), String.join(",", values));
      }
    }

    private void send(Object[] args) {
      if (isSent()) {
        throw new AssertionError("the response was sent twice");
      }
      body = args.length == 0 ? new byte[0] : (byte[]) args[0];
    }
  }
}
