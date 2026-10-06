/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.anthropic.api.AnthropicJson;
import com.standardapplied.helios.anthropic.api.MessagesRequest;
import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.provider.ChatExchange;
import com.standardapplied.helios.core.provider.Exchange;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Continues a turn across {@code pause_turn}. Anthropic pauses a long server-tool turn (web search,
 * web fetch); the turn resumes when the conversation is re-sent with the paused assistant content
 * appended unchanged. The segments merge into one response, so a caller never sees the pause. At
 * most {@link #MAX_PAUSE_CONTINUATIONS} resumes, which turns a pause loop into a loud failure.
 */
final class PauseContinuation implements Exchange<MessagesRequest> {

  /**
   * The most resumes of one turn; the API's own server loop runs about ten iterations a segment.
   */
  static final int MAX_PAUSE_CONTINUATIONS = 8;

  private static final String PAUSE_TURN = "pause_turn";

  private final Exchange<MessagesRequest> exchange;
  private final ChatExchange.StreamOpener<MessagesRequest> segments;

  /**
   * Continues turns segment by segment through {@code exchange}; a paused stream's next segment is
   * opened by {@code segments}, the opener behind {@code exchange}.
   */
  PauseContinuation(
      Exchange<MessagesRequest> exchange, ChatExchange.StreamOpener<MessagesRequest> segments) {
    this.exchange = exchange;
    this.segments = segments;
  }

  /**
   * The whole turn answering {@code request}.
   *
   * @throws AnthropicException if the turn pauses more than {@link #MAX_PAUSE_CONTINUATIONS} times
   */
  @Override
  public Response<Void> chat(MessagesRequest request) {
    Response<Void> merged = null;
    var current = request;
    for (var attempt = 0; attempt <= MAX_PAUSE_CONTINUATIONS; attempt++) {
      var segment = exchange.chat(current);
      merged = merged == null ? segment : SegmentMerge.merge(merged, segment);
      if (!paused(merged)) {
        return merged;
      }
      current = continuation(request, merged);
    }
    throw new AnthropicException(
        "pause_turn continuation limit exceeded after "
            + MAX_PAUSE_CONTINUATIONS
            + " resumes; the server-tool loop did not converge");
  }

  /**
   * The events of the whole turn answering {@code request}: the paused segment's completion is
   * swallowed, the continuation streams on, and the one completion carries the merged response. A
   * failure to open the first segment is the exchange's one error event, passed through.
   */
  @Override
  public CloseableIterator<StreamEvent> stream(MessagesRequest request) {
    return new ContinuingStream(request, exchange.stream(request));
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

  private final class ContinuingStream implements CloseableIterator<StreamEvent> {

    private final MessagesRequest request;
    private CloseableIterator<StreamEvent> current;
    private Response<Void> mergedSoFar;
    private int continuations;

    ContinuingStream(MessagesRequest request, CloseableIterator<StreamEvent> first) {
      this.request = request;
      this.current = first;
    }

    @Override
    public boolean hasNext() {
      return current.hasNext();
    }

    @Override
    public StreamEvent next() {
      var event = current.next();
      while (event instanceof StreamEvent.Done done) {
        var completion = completion(done);
        if (completion != null) {
          return completion;
        }
        event = current.next();
      }
      return event;
    }

    @Override
    public void close() {
      current.close();
    }

    /**
     * The event completing the turn at the end of a segment, or {@code null} once the next segment
     * is open.
     */
    @SuppressWarnings("unchecked")
    private StreamEvent completion(StreamEvent.Done done) {
      var segment = (Response<Void>) done.response();
      var merged = mergedSoFar == null ? segment : SegmentMerge.merge(mergedSoFar, segment);
      if (!paused(merged)) {
        return new StreamEvent.Done(merged);
      }
      if (continuations >= MAX_PAUSE_CONTINUATIONS) {
        return new StreamEvent.Error(
            "pause_turn continuation limit exceeded after " + MAX_PAUSE_CONTINUATIONS + " resumes",
            null);
      }
      mergedSoFar = merged;
      continuations++;
      current.close();
      return resume(merged);
    }

    private StreamEvent resume(Response<Void> merged) {
      try {
        current = segments.open(continuation(request, merged));
        return null;
      } catch (AnthropicException e) {
        return new StreamEvent.Error(e.getMessage(), e);
      } catch (IOException e) {
        return new StreamEvent.Error("Failed to reopen paused stream", e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return new StreamEvent.Error("Request interrupted", e);
      }
    }
  }
}
