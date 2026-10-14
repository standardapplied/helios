/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.anthropic.api.AnthropicJson;
import com.standardapplied.helios.anthropic.api.MessagesRequest;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.provider.Exchange;
import com.standardapplied.helios.core.runtime.CancellationToken;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow;

/**
 * Continues a turn across {@code pause_turn}. Anthropic pauses a long server-tool turn (web search,
 * web fetch); the turn resumes when the conversation is re-sent with the paused assistant content
 * appended unchanged. The segments' events are spliced into one stream: a paused segment's
 * completion is swallowed, the continuation is opened once that segment's stream has completed, and
 * the one terminal carries the merged turn — the completion its response, an error after a pause
 * its partial response. A caller never sees the pause; a continuation's first thinking delta
 * carries the separator the merge puts before its thinking. Demand the caller has not yet been
 * served, or a non-positive request, carries over to the next segment, and cancelling cancels the
 * segment in flight. At most {@link #MAX_PAUSE_CONTINUATIONS} resumes, which turns a pause loop
 * into a loud failure.
 */
final class PauseContinuation implements Exchange<MessagesRequest> {

  /**
   * The most resumes of one turn; the API's own server loop runs about ten iterations a segment.
   */
  static final int MAX_PAUSE_CONTINUATIONS = 8;

  private static final String PAUSE_TURN = "pause_turn";

  private final Exchange<MessagesRequest> exchange;

  /** Continues turns segment by segment through {@code exchange}. */
  PauseContinuation(Exchange<MessagesRequest> exchange) {
    this.exchange = Objects.requireNonNull(exchange, "exchange must not be null");
  }

  /**
   * The events of the whole turn answering {@code request}. A failure to open the first segment is
   * the exchange's one error event, passed through.
   */
  @Override
  public Flow.Publisher<StreamEvent> stream(
      MessagesRequest request, CancellationToken cancellation) {
    var first = exchange.stream(request, cancellation);
    return subscriber -> {
      Objects.requireNonNull(subscriber, "subscriber must not be null");
      first.subscribe(new Turn(request, cancellation, subscriber).new Segment());
    };
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
  private static MessagesRequest continuation(MessagesRequest base, Response<Void> merged) {
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
    var messages = new ArrayList<>(base.messages());
    messages.add(new MessagesRequest.MessageEntry("assistant", blocks));
    return base.continuationWith(messages);
  }

  /**
   * One subscriber's turn: the subscription it holds, spanning every segment. Segments run one
   * after another, so their signals never overlap; the lock guards what the subscriber's own thread
   * shares with them, its demand and the segment in flight.
   */
  private final class Turn implements Flow.Subscription {

    private final MessagesRequest request;
    private final CancellationToken cancellation;
    private final Flow.Subscriber<? super StreamEvent> downstream;
    private final Object lock = new Object();
    private Flow.Subscription current;
    private long demand;
    private boolean cancelled;
    private Long invalidRequest;
    private Response<Void> mergedSoFar;
    private int continuations;

    Turn(
        MessagesRequest request,
        CancellationToken cancellation,
        Flow.Subscriber<? super StreamEvent> downstream) {
      this.request = request;
      this.cancellation = cancellation;
      this.downstream = downstream;
    }

    @Override
    public void request(long n) {
      Flow.Subscription segment;
      synchronized (lock) {
        if (n > 0) {
          demand = demand + n < 0 ? Long.MAX_VALUE : demand + n;
        } else {
          invalidRequest = n;
        }
        segment = current;
      }
      segment.request(n);
    }

    @Override
    public void cancel() {
      Flow.Subscription segment;
      synchronized (lock) {
        cancelled = true;
        segment = current;
      }
      segment.cancel();
    }

    private void deliver(StreamEvent event) {
      synchronized (lock) {
        if (demand != Long.MAX_VALUE) {
          demand--;
        }
      }
      downstream.onNext(event);
    }

    /** One segment's events, relayed to the turn's subscriber. */
    private final class Segment implements Flow.Subscriber<StreamEvent> {

      private MessagesRequest next;
      private boolean thinkingStreamed;

      @Override
      public void onSubscribe(Flow.Subscription subscription) {
        boolean first;
        long carried;
        Long invalid;
        boolean stop;
        synchronized (lock) {
          first = current == null;
          current = subscription;
          carried = demand;
          invalid = invalidRequest;
          stop = cancelled;
        }
        if (first) {
          downstream.onSubscribe(Turn.this);
        } else if (stop) {
          subscription.cancel();
        } else if (invalid != null) {
          subscription.request(invalid);
        } else if (carried > 0) {
          subscription.request(carried);
        }
      }

      @Override
      public void onNext(StreamEvent event) {
        switch (event) {
          case StreamEvent.Done done -> complete(done);
          case StreamEvent.Error(var message, var cause, var partial) when mergedSoFar != null ->
              deliver(
                  new StreamEvent.Error(message, cause, SegmentMerge.merge(mergedSoFar, partial)));
          case StreamEvent.ThinkingDelta delta -> deliver(separated(delta));
          default -> deliver(event);
        }
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
      private void complete(StreamEvent.Done done) {
        var segment = (Response<Void>) done.response();
        var merged = mergedSoFar == null ? segment : SegmentMerge.merge(mergedSoFar, segment);
        if (!paused(merged)) {
          deliver(new StreamEvent.Done(merged));
        } else if (continuations >= MAX_PAUSE_CONTINUATIONS) {
          deliver(
              new StreamEvent.Error(
                  "pause_turn continuation limit exceeded after "
                      + MAX_PAUSE_CONTINUATIONS
                      + " resumes; the server-tool loop did not converge",
                  null,
                  failed(merged)));
        } else {
          resume(merged);
        }
      }

      private void resume(Response<Void> merged) {
        try {
          next = continuation(request, merged);
          mergedSoFar = merged;
          continuations++;
        } catch (AnthropicException e) {
          deliver(new StreamEvent.Error(e.getMessage(), e, failed(merged)));
        }
      }

      @Override
      public void onError(Throwable failure) {
        downstream.onError(failure);
      }

      @Override
      public void onComplete() {
        if (next == null) {
          downstream.onComplete();
          return;
        }
        boolean stop;
        synchronized (lock) {
          stop = cancelled;
        }
        if (!stop) {
          exchange.stream(next, cancellation).subscribe(new Segment());
        }
      }
    }
  }
}
