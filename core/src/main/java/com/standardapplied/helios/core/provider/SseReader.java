/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.ProviderException;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.model.TransientStreamException;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The events of one server-sent-event response, sent on the first {@code hasNext()} when built from
 * a request. Owns the response body: reads it a line at a time, failing a line that does not arrive
 * within the idle timeout, hands the data of each event the lines frame to the provider's {@link
 * Parser}, and closes the body and its reader thread once the stream ends, fails or is closed.
 * Every event the lines frame yields one stream event: an event with no data, or whose data the
 * parser yields nothing for, yields a {@link StreamEvent.KeepAlive}, so a consumer sees each frame
 * as proof the stream is alive.
 *
 * <p>A stream that is not closed first ends with exactly one terminal event: the {@link
 * StreamEvent.Done} or {@link StreamEvent.Error} the parser yields, or an error this reader raises.
 * A body that ends before the parser's terminal is never a completion: it ends in an error whose
 * cause is an {@link EOFException}. A failure to read ends in an error carrying its {@link
 * IOException}, an idle timeout or an oversized line or event one carrying the provider's own
 * exception. Each error carries the parser's {@link Parser#partial()}. Closing the reader from
 * another thread releases a blocked read as such an error, an interrupt of the reading thread
 * closes the reader and throws a {@link CancellationException} with the interrupt kept, even when
 * an event was read ahead; a reader that ended still has nothing next. {@link SseFrames} frames the
 * lines into events.
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

  private static final int OK = 200;

  private static final Response<Void> NOTHING =
      Response.newBuilder().withContent("").withFinishReason(FinishReason.ERROR).build();

  private final SseRequest request;
  private final Duration idleTimeout;
  private final Parser parser;
  private final ProviderFailure failure;
  private final ExecutorService readExecutor = Executors.newVirtualThreadPerTaskExecutor();
  private final Object lock = new Object();
  private InputStream body;
  private Reader reader;
  private SseFrames frames;
  private StreamEvent nextEvent;
  private boolean sent;
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
    this((SseRequest) null, idleTimeout, parser, failure);
    sent = true;
    attach(Objects.requireNonNull(body, "body must not be null"));
  }

  /**
   * Reads the response to {@code request}, of which nothing is sent before the first {@code
   * hasNext()}. That sends it through {@code client} and waits for the headers; {@link #close()}
   * before them cancels the request, after them closes the body, and an interrupt while waiting, or
   * while reading the body of a refusal, cancels it and throws a {@link CancellationException} with
   * the interrupt kept. A failure to open is the stream's one {@link StreamEvent.Error}: a status
   * other than 200 carries the provider's exception with the status and the start of the response
   * body, as the cause of a {@link TransientStreamException} carrying the response's {@code
   * Retry-After} when that status is {@linkplain ProviderException#isRetryable() retryable}; an
   * {@link IOException}, the {@link java.net.http.HttpTimeoutException} of the request's timeout
   * included, is a failure to communicate with {@code apiName}.
   *
   * @param client the client the request goes through
   * @param request the request
   * @param apiName the API a failure to communicate names, e.g. {@code "Anthropic API"}
   * @param idleTimeout the longest wait for one line once the headers arrived
   * @param parser the provider's parser
   * @param failure creates the provider's exception
   * @param providerName the provider name a {@link TransientStreamException} carries
   */
  public SseReader(
      HttpClient client,
      HttpRequest request,
      String apiName,
      Duration idleTimeout,
      Parser parser,
      ProviderFailure failure,
      String providerName) {
    this(new SseRequest(client, request, apiName, providerName), idleTimeout, parser, failure);
  }

  private SseReader(
      SseRequest request, Duration idleTimeout, Parser parser, ProviderFailure failure) {
    this.request = request;
    this.idleTimeout = Objects.requireNonNull(idleTimeout, "idleTimeout must not be null");
    this.parser = Objects.requireNonNull(parser, "parser must not be null");
    this.failure = Objects.requireNonNull(failure, "failure must not be null");
  }

  @Override
  public boolean hasNext() {
    if (Thread.currentThread().isInterrupted() && (!done || nextEvent != null)) {
      close();
      throw new CancellationException("interrupted while reading the stream");
    }
    if (!sent) {
      sent = true;
      nextEvent = open();
    }
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
    synchronized (lock) {
      done = true;
    }
    release();
    if (request != null) {
      request.cancel();
    }
  }

  /**
   * Sends the request and takes its body, or yields the one error a failure to open is; sends
   * nothing and yields nothing once closed. Anything else it throws, an interrupt's cancellation
   * included, ends the stream.
   */
  private StreamEvent open() {
    try {
      var response = request.awaitHeaders();
      if (response == null) {
        return null;
      }
      attach(response.body());
      if (response.statusCode() == OK) {
        return null;
      }
      var refusal = request.refused(response, failure);
      return failed(refusal.getMessage(), refusal, NOTHING);
    } catch (IOException e) {
      return failed("Failed to communicate with " + request.apiName(), e, NOTHING);
    } catch (RuntimeException | Error e) {
      release();
      throw e;
    }
  }

  /** Takes {@code opened} as the body to read, or closes it when the reader is closed already. */
  private void attach(InputStream opened) {
    synchronized (lock) {
      if (done) {
        closeQuietly(opened);
        throw new CancellationException("the stream was closed before its response arrived");
      }
      body = opened;
      reader = new InputStreamReader(opened, StandardCharsets.UTF_8);
      frames = new SseFrames(reader, failure);
    }
  }

  private StreamEvent readNextEvent() {
    try {
      var payload = frames.payload(this::readLine);
      if (payload == null) {
        return failed(
            "Stream ended before its terminal event",
            new EOFException("the response body ended before the provider's terminal event"),
            parser.partial());
      }
      var event = payload.isEmpty() ? null : parser.parse(payload);
      if (event instanceof StreamEvent.Done || event instanceof StreamEvent.Error) {
        release();
      }
      return event == null ? new StreamEvent.KeepAlive() : event;
    } catch (ProviderException e) {
      return failed(e.getMessage(), e, parser.partial());
    } catch (IOException e) {
      return failed("Stream read error", e, parser.partial());
    }
  }

  private StreamEvent failed(String message, Exception cause, Response<Void> partial) {
    release();
    return new StreamEvent.Error(message, cause, partial);
  }

  /** Ends the stream and releases its body and reader thread. */
  private void release() {
    InputStream openBody;
    Reader openReader;
    synchronized (lock) {
      done = true;
      openBody = body;
      openReader = reader;
    }
    nextEvent = null;
    readExecutor.shutdownNow();
    if (openBody != null) {
      closeQuietly(openBody);
      closeQuietly(openReader);
    }
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
      release();
      Thread.currentThread().interrupt();
      throw new CancellationException("interrupted while waiting for the next line");
    }
  }
}
