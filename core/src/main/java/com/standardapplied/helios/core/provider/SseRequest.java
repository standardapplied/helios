/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import com.standardapplied.helios.core.common.HttpClientFactory;
import com.standardapplied.helios.core.model.TransientStreamException;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * The one request a lazy {@link SseReader} sends: sent with {@link HttpClient#sendAsync} when its
 * reader first reads, and cancellable from any thread, before it is sent or while its headers are
 * awaited. A status other than 200 is refused with the provider's exception.
 */
final class SseRequest {

  private final HttpClient client;
  private final HttpRequest request;
  private final String apiName;
  private final String providerName;
  private final Object lock = new Object();
  private CompletableFuture<HttpResponse<InputStream>> pending;
  private boolean cancelled;

  SseRequest(HttpClient client, HttpRequest request, String apiName, String providerName) {
    this.client = client;
    this.request = request;
    this.apiName = apiName;
    this.providerName = providerName;
  }

  /** The API a failure to communicate names. */
  String apiName() {
    return apiName;
  }

  /**
   * Sends the request and waits for the response's headers.
   *
   * @return the response, or {@code null} when the request was cancelled before it was sent
   * @throws IOException if the request cannot be sent or its headers do not arrive
   * @throws CancellationException if cancelled while waiting, or if the waiting thread is
   *     interrupted, which cancels the request and stays set
   */
  HttpResponse<InputStream> awaitHeaders() throws IOException {
    CompletableFuture<HttpResponse<InputStream>> response;
    synchronized (lock) {
      if (cancelled) {
        return null;
      }
      response = client.sendAsync(request, BodyHandlers.ofInputStream());
      pending = response;
    }
    try {
      return response.get();
    } catch (InterruptedException e) {
      cancel();
      discard(response);
      Thread.currentThread().interrupt();
      var cancelled =
          new CancellationException("interrupted while waiting for the response headers");
      cancelled.initCause(e);
      throw cancelled;
    } catch (ExecutionException e) {
      throw switch (e.getCause()) {
        case IOException io -> io;
        case RuntimeException unchecked -> throw unchecked;
        case Error error -> throw error;
        case Throwable other -> new IOException(other);
      };
    }
  }

  /**
   * Closes the body of a response that arrived as its wait was interrupted, too late for the
   * cancel: no reader takes it.
   */
  private static void discard(CompletableFuture<HttpResponse<InputStream>> response) {
    if (response.state() == Future.State.SUCCESS) {
      try {
        response.resultNow().body().close();
      } catch (IOException ignored) {
        // The response is abandoned either way; a failed close leaves nothing more to release.
      }
    }
  }

  /** Cancels the request: it is never sent, or its pending response is abandoned. */
  void cancel() {
    CompletableFuture<HttpResponse<InputStream>> response;
    synchronized (lock) {
      cancelled = true;
      response = pending;
    }
    if (response != null) {
      response.cancel(true);
    }
  }

  /**
   * The exception of a response whose status is not 200, carrying the status and the start of its
   * body, which this closes; when that status is retryable, the cause of a {@link
   * TransientStreamException} carrying the response's {@code Retry-After}.
   *
   * @throws IOException if the body cannot be read
   */
  RuntimeException refused(HttpResponse<InputStream> response, ProviderFailure failure)
      throws IOException {
    var status = response.statusCode();
    String text;
    try (var errorBody = response.body()) {
      text = HttpClientFactory.readBoundedErrorBody(errorBody);
    }
    var exception = failure.create("API error (status " + status + "): " + text, status, null);
    return exception.isRetryable()
        ? new TransientStreamException(
            exception.getMessage(), exception, providerName, RetryAfter.of(response.headers()))
        : exception;
  }
}
