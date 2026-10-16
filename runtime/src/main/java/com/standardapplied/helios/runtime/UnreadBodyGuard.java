/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import io.helidon.common.Weighted;
import io.helidon.http.BadRequestException;
import io.helidon.http.DirectHandler.EventType;
import io.helidon.http.HeaderValues;
import io.helidon.http.HttpException;
import io.helidon.http.RequestException;
import io.helidon.http.Status;
import io.helidon.webserver.http.DirectTransportRequest;
import io.helidon.webserver.http.HttpFeature;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;

/**
 * Ends the connection after every answer that leaves its request body unread, so no byte a client
 * sends after that answer is read, drained or parsed as a request.
 *
 * <p>On a kept-alive HTTP/1.1 connection Helidon follows such an answer by draining the rest of the
 * body, outside every bound, or, when the client sent {@code Expect: 100-continue} and no {@code
 * 100 Continue} went out, by parsing the bytes the client sends next as a new request: one smuggled
 * past whatever checked the first request's headers. This guard sets {@code Connection: close} on
 * any response sent while the request's body is not read to its end; Helidon then writes and
 * flushes the complete response, half-closes the socket and ends the connection without reading
 * further. A request whose body was read to its end, or that has none, keeps its connection.
 *
 * <p>Installed on a routing with {@code routing.addFeature(new UnreadBodyGuard())}, it covers every
 * route and filter of that routing, another feature's included, and Helidon's own answers on it: no
 * matching route, and any exception a route lets escape. It answers such an exception itself, with
 * the status, headers and message Helidon's direct handlers give it, because Helidon's own error
 * handling drains an unread body before it answers; an exception that is not an {@link
 * HttpException} is a {@code 500} with a fixed message. A {@link BadRequestException} it leaves to
 * Helidon, which answers it on the connection itself and ends it without reading further. Its
 * handler for {@link Throwable} takes the place of one the routing registers for {@code Throwable}
 * itself; a handler for a narrower type, such as {@link Exception}, still answers that type, and
 * its answer is covered too. {@link RuntimeServer} installs the guard; {@link AgentHttpService}
 * also applies it to the answers its own routes send, so those are covered on a server that does
 * not install it, where Helidon still drains the unread body of a request whose route lets an
 * exception escape.
 *
 * <p>Two answers are out of the guard's reach, and Helidon drains the unread body without bound
 * before sending them: the one for a route that returns without sending a response, and the one for
 * an error handler of the routing's own that sends none. Every route and error handler on a guarded
 * routing must send its response; each of {@link AgentHttpService}'s does. Closing a connection
 * whose body is unread makes the operating system reset it, which discards response bytes not yet
 * sent and ends retransmission of any lost: a large response, or one whose packet is lost, can
 * reach the client cut short. Each of Helios's refusals fits in one packet.
 */
public final class UnreadBodyGuard implements HttpFeature, Weighted {

  private static final String INTERNAL_ERROR = "Internal Server Error";

  /** Build the guard. */
  public UnreadBodyGuard() {}

  @Override
  public void setup(HttpRouting.Builder routing) {
    routing
        .addFilter(
            (chain, req, resp) -> {
              apply(req, resp);
              chain.proceed();
            })
        .error(Throwable.class, UnreadBodyGuard::answer);
  }

  /**
   * Set up before every other feature, so that an answer another feature's filter sends without
   * proceeding is covered too; a feature sets up its filters in weight order, highest first.
   */
  @Override
  public double weight() {
    return Double.MAX_VALUE;
  }

  /** Close the connection after {@code resp} if, when it is sent, {@code req}'s body is unread. */
  static void apply(ServerRequest req, ServerResponse resp) {
    resp.beforeSend(
        () -> {
          if (!req.content().consumed()) {
            resp.header(HeaderValues.CONNECTION_CLOSE);
          }
        });
  }

  private static void answer(ServerRequest req, ServerResponse resp, Throwable thrown) {
    if (thrown instanceof BadRequestException e) {
      throw e;
    }
    var failure = failure(req, thrown);
    req.listenerContext().directHandlers().handle(failure, resp, failure.keepAlive());
  }

  private static RequestException failure(ServerRequest req, Throwable thrown) {
    var failure =
        RequestException.builder()
            .cause(thrown)
            .request(DirectTransportRequest.create(req.prologue(), req.headers()));
    if (thrown instanceof HttpException e) {
      return failure
          .type(EventType.OTHER)
          .status(e.status())
          .message(e.getMessage())
          .setKeepAlive(e.keepAlive())
          .update(it -> e.headers().forEach(it::header))
          .build();
    }
    return failure
        .type(EventType.INTERNAL_ERROR)
        .status(Status.INTERNAL_SERVER_ERROR_500)
        .message(INTERNAL_ERROR)
        .build();
  }
}
