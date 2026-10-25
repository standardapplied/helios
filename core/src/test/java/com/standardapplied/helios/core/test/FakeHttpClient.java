/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * An {@link HttpClient} that answers every request through a function the test supplies, for an
 * outcome no real server can force: a failure the client itself reports, or a response that arrives
 * at a chosen point. Responses are bodies of {@link InputStream}; a client built for {@code send}
 * refuses {@code sendAsync} and the other way round.
 */
public final class FakeHttpClient extends HttpClient {

  /** Answers one request sent with {@code send}. */
  @FunctionalInterface
  public interface Answer {

    /**
     * The response to {@code request}.
     *
     * @throws IOException as {@code send} would
     * @throws InterruptedException as {@code send} would
     */
    HttpResponse<InputStream> answer(HttpRequest request) throws IOException, InterruptedException;
  }

  private final Answer send;
  private final Function<HttpRequest, CompletableFuture<HttpResponse<InputStream>>> sendAsync;

  private FakeHttpClient(
      Answer send, Function<HttpRequest, CompletableFuture<HttpResponse<InputStream>>> sendAsync) {
    this.send = send;
    this.sendAsync = sendAsync;
  }

  /** A client whose {@code send} answers through {@code answer}. */
  public static FakeHttpClient sending(Answer answer) {
    return new FakeHttpClient(
        answer,
        request -> {
          throw new UnsupportedOperationException("sendAsync");
        });
  }

  /** A client whose {@code sendAsync} answers through {@code answer}. */
  public static FakeHttpClient sendingAsync(
      Function<HttpRequest, CompletableFuture<HttpResponse<InputStream>>> answer) {
    return new FakeHttpClient(
        request -> {
          throw new UnsupportedOperationException("send");
        },
        answer);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
      throws IOException, InterruptedException {
    return (HttpResponse<T>) send.answer(request);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request, HttpResponse.BodyHandler<T> handler) {
    return (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>) sendAsync.apply(request);
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request,
      HttpResponse.BodyHandler<T> handler,
      HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
    throw new UnsupportedOperationException("push promises");
  }

  @Override
  public Optional<CookieHandler> cookieHandler() {
    return Optional.empty();
  }

  @Override
  public Optional<Duration> connectTimeout() {
    return Optional.of(Duration.ofSeconds(1));
  }

  @Override
  public Redirect followRedirects() {
    return Redirect.NEVER;
  }

  @Override
  public Optional<ProxySelector> proxy() {
    return Optional.empty();
  }

  @Override
  public SSLContext sslContext() {
    return null;
  }

  @Override
  public SSLParameters sslParameters() {
    return new SSLParameters();
  }

  @Override
  public Optional<Authenticator> authenticator() {
    return Optional.empty();
  }

  @Override
  public Version version() {
    return Version.HTTP_2;
  }

  @Override
  public Optional<Executor> executor() {
    return Optional.empty();
  }
}
