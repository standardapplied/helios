/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import javax.net.ssl.SSLSession;

/**
 * A response a {@link FakeHttpClient} answers with: a status, headers, and a body that is what a
 * supplier gives each time it is asked, so a test can act at the moment the body is taken.
 */
public final class FakeHttpResponse implements HttpResponse<InputStream> {

  private final HttpRequest request;
  private final int status;
  private final Map<String, List<String>> headers;
  private final Supplier<InputStream> body;

  /** A response to {@code request} with {@code status}, {@code headers} and {@code body}. */
  public FakeHttpResponse(
      HttpRequest request,
      int status,
      Map<String, List<String>> headers,
      Supplier<InputStream> body) {
    this.request = request;
    this.status = status;
    this.headers = headers;
    this.body = body;
  }

  @Override
  public int statusCode() {
    return status;
  }

  @Override
  public HttpHeaders headers() {
    return HttpHeaders.of(headers, (name, value) -> true);
  }

  @Override
  public InputStream body() {
    return body.get();
  }

  @Override
  public Optional<HttpResponse<InputStream>> previousResponse() {
    return Optional.empty();
  }

  @Override
  public HttpRequest request() {
    return request;
  }

  @Override
  public Optional<SSLSession> sslSession() {
    return Optional.empty();
  }

  @Override
  public URI uri() {
    return request.uri();
  }

  @Override
  public HttpClient.Version version() {
    return HttpClient.Version.HTTP_2;
  }
}
