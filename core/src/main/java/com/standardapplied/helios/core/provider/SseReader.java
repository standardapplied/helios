/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import com.standardapplied.helios.core.common.HttpClientFactory;
import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.ProviderException;
import com.standardapplied.helios.core.model.StreamEvent;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The events of one server-sent-event response. Owns the response body: reads it a line at a time,
 * failing a line that does not arrive within the idle timeout, hands each {@code data:} payload to
 * the provider's {@link Parser}, and closes the body and its reader thread once the stream ends,
 * fails or is closed. A failure to read becomes a terminal {@link StreamEvent.Error}: an idle
 * timeout carries the provider's own exception, any other read failure its {@link IOException}.
 */
public final class SseReader implements CloseableIterator<StreamEvent> {

  /** A provider's interpretation of the payloads of one stream. */
  public interface Parser {

    /**
     * Absorbs one {@code data:} payload. Never throws: a payload it cannot read becomes a {@link
     * StreamEvent.Error}.
     *
     * @param payload the payload, trimmed; never empty or {@code [DONE]}
     * @return the event the payload yields, or {@code null} when it yields none
     */
    StreamEvent parse(String payload);

    /** Whether the payload last parsed ended the stream. */
    boolean finished();

    /** The event that completes a stream whose body ended without a terminal payload. */
    StreamEvent complete();
  }

  private static final String DATA_PREFIX = "data: ";
  private static final String DONE_MARKER = "[DONE]";

  private final InputStream body;
  private final BufferedReader reader;
  private final Duration idleTimeout;
  private final Parser parser;
  private final ProviderFailure failure;
  private final ExecutorService readExecutor = Executors.newVirtualThreadPerTaskExecutor();
  private StreamEvent nextEvent;
  private boolean done;

  /**
   * Reads {@code body}, which this reader now owns.
   *
   * @param body the response body
   * @param idleTimeout the longest wait for one line
   * @param parser the provider's parser
   * @param failure creates the exception an idle timeout carries
   */
  public SseReader(InputStream body, Duration idleTimeout, Parser parser, ProviderFailure failure) {
    this.body = body;
    this.reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
    this.idleTimeout = idleTimeout;
    this.parser = parser;
    this.failure = failure;
  }

  /**
   * Sends {@code request} and reads its response as a stream. A status other than 200 fails with
   * the provider's exception, carrying the status and the start of the response body.
   *
   * @return the reader of the response
   * @throws IOException if the request cannot be sent
   * @throws InterruptedException if interrupted while sending
   */
  public static SseReader open(
      HttpClient client,
      HttpRequest request,
      Duration idleTimeout,
      Parser parser,
      ProviderFailure failure)
      throws IOException, InterruptedException {
    var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    if (response.statusCode() != 200) {
      try (var errorBody = response.body()) {
        var detail = HttpClientFactory.readBoundedErrorBody(errorBody);
        throw failure.create(
            "API error (status " + response.statusCode() + "): " + detail,
            response.statusCode(),
            null);
      }
    }
    return new SseReader(response.body(), idleTimeout, parser, failure);
  }

  @Override
  public boolean hasNext() {
    if (done) {
      return false;
    }
    if (nextEvent == null) {
      nextEvent = readNextEvent();
    }
    return nextEvent != null;
  }

  @Override
  public StreamEvent next() {
    if (nextEvent == null) {
      nextEvent = readNextEvent();
    }
    var event = nextEvent;
    nextEvent = null;
    return event;
  }

  @Override
  public void close() {
    done = true;
    readExecutor.shutdownNow();
    closeQuietly(body);
    closeQuietly(reader);
  }

  private StreamEvent readNextEvent() {
    try {
      for (var payload = nextPayload(); payload != null; payload = nextPayload()) {
        var event = parser.parse(payload);
        if (parser.finished()) {
          close();
          return event;
        }
        if (event != null) {
          return event;
        }
      }
      close();
      return parser.complete();
    } catch (ProviderException e) {
      close();
      return new StreamEvent.Error(e.getMessage(), e);
    } catch (IOException e) {
      close();
      return new StreamEvent.Error("Stream read error", e);
    }
  }

  private String nextPayload() throws IOException {
    for (var line = readLine(); line != null; line = readLine()) {
      if (line.startsWith(DATA_PREFIX)) {
        var payload = line.substring(DATA_PREFIX.length()).trim();
        if (!payload.isEmpty() && !DONE_MARKER.equals(payload)) {
          return payload;
        }
      }
    }
    return null;
  }

  private static void closeQuietly(Closeable closeable) {
    try {
      closeable.close();
    } catch (IOException ignored) {
      // The stream is abandoned either way; a failed close leaves nothing more to release.
    }
  }

  private String readLine() throws IOException {
    var line = readExecutor.submit((Callable<String>) reader::readLine);
    try {
      return line.get(idleTimeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      line.cancel(true);
      throw failure.create(
          "Stream idle timeout: no data received for " + idleTimeout.toSeconds() + "s", 0, null);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof IOException ioe) {
        throw ioe;
      }
      throw new IOException("Stream read failed", e.getCause());
    } catch (InterruptedException e) {
      line.cancel(true);
      Thread.currentThread().interrupt();
      throw new IOException("Stream read interrupted", e);
    }
  }
}
