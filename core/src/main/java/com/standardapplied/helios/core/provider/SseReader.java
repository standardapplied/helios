/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import com.standardapplied.helios.core.common.HttpClientFactory;
import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.ProviderException;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The events of one server-sent-event response. Owns the response body: reads it a line at a time,
 * failing a line that does not arrive within the idle timeout, hands the data of each event the
 * lines frame to the provider's {@link Parser}, and closes the body and its reader thread once the
 * stream ends, fails or is closed. Every event the lines frame yields one stream event: an event
 * with no data, or whose data the parser yields nothing for, yields a {@link
 * StreamEvent.KeepAlive}, so a consumer sees each frame as proof the stream is alive.
 *
 * <p>A stream ends with exactly one terminal event: the {@link StreamEvent.Done} or {@link
 * StreamEvent.Error} the parser yields, or an error this reader raises. A body that ends before the
 * parser's terminal is never a completion: it ends in an error whose cause is an {@link
 * EOFException}. A failure to read ends in an error carrying its {@link IOException}, an idle
 * timeout or an oversized line or event one carrying the provider's own exception. Each error
 * carries the parser's {@link Parser#partial()}. {@link SseFrames} frames the lines into events.
 */
public final class SseReader implements CloseableIterator<StreamEvent> {

  /** A provider's interpretation of the events of one stream. */
  public interface Parser {

    /**
     * Absorbs one event's data. Never throws: data it cannot read becomes a {@link
     * StreamEvent.Error}. A {@link StreamEvent.Done} or {@link StreamEvent.Error} ends the stream.
     *
     * @param payload the event's data, trimmed; never empty or {@code [DONE]}
     * @return the event the payload yields, or {@code null} when it yields none, which the reader
     *     passes on as a {@link StreamEvent.KeepAlive}
     */
    StreamEvent parse(String payload);

    /**
     * What the stream has produced so far, finishing with {@link
     * com.standardapplied.helios.core.model.FinishReason#ERROR}: the content of a stream that ends
     * without its terminal event.
     */
    Response<Void> partial();
  }

  private final InputStream body;
  private final Reader reader;
  private final SseFrames frames;
  private final Duration idleTimeout;
  private final Parser parser;
  private final ProviderFailure failure;
  private final ExecutorService readExecutor = Executors.newVirtualThreadPerTaskExecutor();
  private StreamEvent nextEvent;
  private volatile boolean done;

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
    this.reader = new InputStreamReader(body, StandardCharsets.UTF_8);
    this.frames = new SseFrames(reader, failure);
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
      throw failure.create(
          "API error (status " + response.statusCode() + "): " + errorBody(response.body()),
          response.statusCode(),
          null);
    }
    return new SseReader(response.body(), idleTimeout, parser, failure);
  }

  private static String errorBody(InputStream body) throws IOException {
    try (body) {
      return HttpClientFactory.readBoundedErrorBody(body);
    }
  }

  @Override
  public boolean hasNext() {
    if (nextEvent == null && !done) {
      nextEvent = readNextEvent();
    }
    return nextEvent != null;
  }

  @Override
  public StreamEvent next() {
    if (!hasNext()) {
      throw new NoSuchElementException("The stream has ended");
    }
    var event = nextEvent;
    nextEvent = null;
    return event;
  }

  @Override
  public void close() {
    done = true;
    nextEvent = null;
    readExecutor.shutdownNow();
    closeQuietly(body);
    closeQuietly(reader);
  }

  private StreamEvent readNextEvent() {
    try {
      var payload = frames.payload(this::readLine);
      if (payload == null) {
        return failed(
            "Stream ended before its terminal event",
            new EOFException("the response body ended before the provider's terminal event"));
      }
      var event = payload.isEmpty() ? null : parser.parse(payload);
      if (event instanceof StreamEvent.Done || event instanceof StreamEvent.Error) {
        close();
      }
      return event == null ? new StreamEvent.KeepAlive() : event;
    } catch (ProviderException e) {
      return failed(e.getMessage(), e);
    } catch (IOException e) {
      return failed("Stream read error", e);
    }
  }

  private StreamEvent failed(String message, Exception cause) {
    close();
    return new StreamEvent.Error(message, cause, parser.partial());
  }

  private static void closeQuietly(Closeable closeable) {
    try {
      closeable.close();
    } catch (IOException ignored) {
      // The stream is abandoned either way; a failed close leaves nothing more to release.
    }
  }

  private String readLine() throws IOException {
    Future<String> line;
    try {
      line = readExecutor.submit((Callable<String>) frames::line);
    } catch (RejectedExecutionException closed) {
      throw new IOException("Stream closed", closed);
    }
    try {
      return line.get(idleTimeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      line.cancel(true);
      throw failure.create(
          "Stream idle timeout: no data received for " + idleTimeout.toMillis() + " ms", 0, null);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof IOException ioe) {
        throw ioe;
      }
      if (e.getCause() instanceof ProviderException tooLarge) {
        throw tooLarge;
      }
      throw new IOException("Stream read failed", e.getCause());
    } catch (InterruptedException e) {
      line.cancel(true);
      Thread.currentThread().interrupt();
      throw new IOException("Stream read interrupted", e);
    }
  }
}
