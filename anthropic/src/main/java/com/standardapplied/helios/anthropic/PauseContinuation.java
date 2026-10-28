/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.anthropic.api.AnthropicJson;
import com.standardapplied.helios.anthropic.api.MessagesRequest;
import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.provider.Exchange;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * Continues a turn across {@code pause_turn}. Anthropic pauses a long server-tool turn (web search,
 * web fetch); the turn resumes when the conversation is re-sent with the paused assistant content
 * appended unchanged. The segments' events are spliced into one stream: a paused segment's
 * completion is swallowed, that segment's stream closed, and the continuation opened on the next
 * {@code hasNext()}; the one terminal carries the merged turn — the completion its response, an
 * error after a pause its partial response. A caller never sees the pause; a continuation's first
 * thinking delta carries the separator the merge puts before its thinking, and every segment is
 * sent under the turn's one prompt stamp. Closing closes the segment in flight and opens no further
 * one. At most {@link #MAX_PAUSE_CONTINUATIONS} resumes, which turns a pause loop into a loud
 * failure.
 */
final class PauseContinuation implements Exchange<StampedRequest> {

  /**
   * The most resumes of one turn; the API's own server loop runs about ten iterations a segment.
   */
  static final int MAX_PAUSE_CONTINUATIONS = 8;

  private static final String PAUSE_TURN = "pause_turn";

  private final Exchange<StampedRequest> exchange;

  /** Continues turns segment by segment through {@code exchange}. */
  PauseContinuation(Exchange<StampedRequest> exchange) {
    this.exchange = Objects.requireNonNull(exchange, "exchange must not be null");
  }

  /**
   * The events of the whole turn answering {@code request}. A failure to open the first segment is
   * the exchange's one error event, passed through.
   */
  @Override
  public CloseableIterator<StreamEvent> stream(StampedRequest request) {
    return new Turn(request, exchange.stream(request));
  }

  /** {@code merged}, the turn so far, as the partial response of a turn that failed. */
  private static Response<Void> failed(Response<Void> merged) {
    return Response.newBuilder()
        .withContent(merged.content())
        .withToolCalls(merged.toolCalls())
        .withFinishReason(FinishReason.ERROR)
        .withUsage(merged.usage())
        .withThinking(merged.thinking())
        .withCitations(merged.citations())
        .withMetadata(merged.metadata())
        .build();
  }

  private static boolean paused(Response<Void> response) {
    return PAUSE_TURN.equals(response.metadata().get(AnthropicResponseAssembler.STOP_REASON_KEY));
  }

  /** {@code base} with the merged-so-far assistant content appended verbatim. */
  @SuppressWarnings("unchecked")
  private static StampedRequest continuation(StampedRequest base, Response<Void> merged) {
    var rawContent = merged.metadata().get(RawContentEcho.RAW_CONTENT_KEY);
    if (rawContent == null || rawContent.isEmpty()) {
      throw new AnthropicException(
          "pause_turn received without capturable assistant content; cannot resume");
    }
    List<Object> blocks;
    try {
      blocks = (List<Object>) AnthropicJson.LENIENT.readValue(rawContent, List.class);
    } catch (RuntimeException e) {
      throw new AnthropicException("Failed to decode paused assistant content for resume", e);
    }
    var messages = new ArrayList<>(base.body().messages());
    messages.add(new MessagesRequest.MessageEntry("assistant", blocks));
    return base.continuationWith(messages);
  }

  /**
   * One turn's events, segment after segment. The lock guards the segment in flight, which {@link
   * #close()} may close from another thread while the reader opens the next one.
   */
  private final class Turn implements CloseableIterator<StreamEvent> {

    private final StampedRequest request;
    private final Object lock = new Object();
    private CloseableIterator<StreamEvent> segment;
    private boolean closed;
    private StampedRequest continuation;
    private Response<Void> mergedSoFar;
    private int continuations;
    private boolean thinkingStreamed;
    private StreamEvent next;
    private boolean ended;

    Turn(StampedRequest request, CloseableIterator<StreamEvent> first) {
      this.request = request;
      this.segment = first;
    }

    @Override
    public boolean hasNext() {
      while (next == null && !ended) {
        if (continuation != null) {
          openContinuation();
        }
        if (current().hasNext()) {
          next = relay(current().next());
        } else {
          ended = true;
        }
      }
      return next != null;
    }

    @Override
    public StreamEvent next() {
      if (!hasNext()) {
        throw new NoSuchElementException("the turn's stream has ended");
      }
      var event = next;
      next = null;
      return event;
    }

    @Override
    public void close() {
      CloseableIterator<StreamEvent> inFlight;
      synchronized (lock) {
        closed = true;
        inFlight = segment;
      }
      inFlight.close();
    }

    private CloseableIterator<StreamEvent> current() {
      synchronized (lock) {
        return segment;
      }
    }

    private void openContinuation() {
      var paused = current();
      paused.close();
      var opened = exchange.stream(continuation);
      continuation = null;
      thinkingStreamed = false;
      synchronized (lock) {
        if (!closed) {
          segment = opened;
          return;
        }
      }
      opened.close();
      throw new CancellationException("the turn's stream was closed during a pause");
    }

    /** {@code event} as the turn yields it, or {@code null} for a paused segment's completion. */
    private StreamEvent relay(StreamEvent event) {
      return switch (event) {
        case StreamEvent.Done done -> complete(done);
        case StreamEvent.Error(var message, var cause, var partial) when mergedSoFar != null ->
            end(new StreamEvent.Error(message, cause, SegmentMerge.merge(mergedSoFar, partial)));
        case StreamEvent.Error error -> end(error);
        case StreamEvent.ThinkingDelta delta -> separated(delta);
        default -> event;
      };
    }

    /**
     * {@code delta}, the segment's first thinking prefixed with the separator the merge puts
     * between the turn's thinking so far and this segment's, so the deltas add up to the turn's.
     */
    private StreamEvent separated(StreamEvent.ThinkingDelta delta) {
      var first = !thinkingStreamed;
      thinkingStreamed = true;
      return first && mergedSoFar != null && mergedSoFar.thinking() != null
          ? new StreamEvent.ThinkingDelta(ThinkingBlocks.SEPARATOR + delta.text())
          : delta;
    }

    /** Completes the turn at the end of a segment, or arranges the next segment's opening. */
    @SuppressWarnings("unchecked")
    private StreamEvent complete(StreamEvent.Done done) {
      var segmentResponse = (Response<Void>) done.response();
      var merged =
          mergedSoFar == null ? segmentResponse : SegmentMerge.merge(mergedSoFar, segmentResponse);
      if (!paused(merged)) {
        return end(new StreamEvent.Done(merged));
      }
      if (continuations >= MAX_PAUSE_CONTINUATIONS) {
        return end(
            new StreamEvent.Error(
                "pause_turn continuation limit exceeded after "
                    + MAX_PAUSE_CONTINUATIONS
                    + " resumes; the server-tool loop did not converge",
                null,
                failed(merged)));
      }
      try {
        continuation = continuation(request, merged);
      } catch (AnthropicException e) {
        return end(new StreamEvent.Error(e.getMessage(), e, failed(merged)));
      }
      mergedSoFar = merged;
      continuations++;
      return null;
    }

    private StreamEvent end(StreamEvent terminal) {
      ended = true;
      current().close();
      return terminal;
    }
  }
}
