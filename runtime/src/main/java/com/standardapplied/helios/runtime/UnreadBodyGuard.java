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
 * <p>Helidon would otherwise keep the connection: it drains the rest of the body outside every
 * bound or, after {@code Expect: 100-continue} with no {@code 100 Continue} sent, parses the next
 * bytes as a new request, smuggled past whatever checked the first one's headers. The guard sets
 * {@code Connection: close} on any response sent while the body is unread; Helidon then flushes the
 * whole response and ends the connection (Helidon 4.5.4 or later; earlier releases ignore {@code
 * Connection: close} here). A request whose body was read to its end, or that has none, keeps its
 * connection.
 *
 * <p>Install it with {@code routing.addFeature(new UnreadBodyGuard())}; {@link RuntimeServer} does.
 * It then covers:
 *
 * <ul>
 *   <li>every route and filter of the routing, another feature's included, since it sets up first;
 *   <li>Helidon's answer to an unmatched path;
 *   <li>an exception a route lets escape, which it answers itself through Helidon's direct handlers
 *       with the same status, headers and message (a fixed {@code 500} for one that is not an
 *       {@link HttpException}), because Helidon's error handling drains before it answers. A {@link
 *       BadRequestException} is left to Helidon, which answers and closes. Its {@link Throwable}
 *       handler replaces one the routing registers for {@code Throwable}; a handler for a narrower
 *       type still answers, and is covered.
 * </ul>
 *
 * <p>{@link AgentHttpService} also applies it to each answer its routes send, so those are covered
 * on a server without the feature; there Helidon still drains the body when a route lets an
 * exception escape.
 *
 * <p>Out of its reach is a route, or a routing's own error handler, that sends no response: Helidon
 * drains the body without bound first. Every route and error handler on a guarded routing must send
 * its response; each of {@link AgentHttpService}'s does.
 *
 * <p>Closing a connection whose body is unread makes the operating system reset it, discarding
 * response bytes not yet sent and ending retransmission, so a large response, or one whose packet
 * is lost, can arrive cut short. Each of Helios's refusals fits in one packet.
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
