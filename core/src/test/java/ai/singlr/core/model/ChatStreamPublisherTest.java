/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.core.tool.Tool;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ChatStreamPublisherTest {

  /** Captures emissions from a Flow.Publisher into an in-memory log. */
  private static class CapturingSubscriber implements Flow.Subscriber<ModelChunk> {

    final List<ModelChunk> chunks = new ArrayList<>();
    final AtomicReference<Throwable> error = new AtomicReference<>();
    final AtomicReference<Boolean> completed = new AtomicReference<>(false);
    Flow.Subscription subscription;
    long initialRequest = Long.MAX_VALUE;

    @Override
    public void onSubscribe(Flow.Subscription s) {
      this.subscription = s;
      if (initialRequest > 0) {
        s.request(initialRequest);
      }
    }

    @Override
    public void onNext(ModelChunk chunk) {
      chunks.add(chunk);
    }

    @Override
    public void onError(Throwable t) {
      error.set(t);
    }

    @Override
    public void onComplete() {
      completed.set(true);
    }
  }

  private static Model textOnly(String text) {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        return Response.newBuilder()
            .withContent(text)
            .withFinishReason(FinishReason.STOP)
            .withUsage(Response.Usage.of(5, 3))
            .build();
      }

      @Override
      public String id() {
        return "test";
      }

      @Override
      public String provider() {
        return "test";
      }
    };
  }

  private static Model withToolCalls(String text, List<ToolCall> calls) {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        return Response.newBuilder()
            .withContent(text)
            .withToolCalls(calls)
            .withFinishReason(FinishReason.TOOL_CALLS)
            .withUsage(Response.Usage.of(8, 4))
            .build();
      }

      @Override
      public String id() {
        return "test";
      }

      @Override
      public String provider() {
        return "test";
      }
    };
  }

  @Test
  void textOnlyResponseEmitsTextDeltaAndMessageStop() {
    var sub = new CapturingSubscriber();
    textOnly("hello world")
        .chatStream(List.of(), List.of(), new CancellationToken())
        .subscribe(sub);

    assertEquals(2, sub.chunks.size());
    assertInstanceOf(ModelChunk.TextDelta.class, sub.chunks.get(0));
    assertEquals("hello world", ((ModelChunk.TextDelta) sub.chunks.get(0)).text());
    var stop = assertInstanceOf(ModelChunk.MessageStop.class, sub.chunks.get(1));
    assertEquals("STOP", stop.stopReason());
    assertEquals(5, stop.usage().inputTokens());
    assertEquals(3, stop.usage().outputTokens());
    assertTrue(sub.completed.get());
    assertNull(sub.error.get());
  }

  @Test
  void responseCitationsForwardOntoMessageStop() {
    var cite = Citation.of("https://vertexaisearch.example/redirect/abc", "snippet");
    Model grounded =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            return Response.newBuilder()
                .withContent("grounded answer")
                .withFinishReason(FinishReason.STOP)
                .withUsage(Response.Usage.of(5, 3))
                .withCitations(List.of(cite))
                .build();
          }

          @Override
          public String id() {
            return "test";
          }

          @Override
          public String provider() {
            return "test";
          }
        };
    var sub = new CapturingSubscriber();
    grounded.chatStream(List.of(), List.of(), new CancellationToken()).subscribe(sub);

    var stop =
        assertInstanceOf(ModelChunk.MessageStop.class, sub.chunks.get(sub.chunks.size() - 1));
    assertEquals(List.of(cite), stop.citations());
  }

  @Test
  void emptyContentEmitsOnlyMessageStop() {
    var sub = new CapturingSubscriber();
    textOnly("").chatStream(List.of(), List.of(), new CancellationToken()).subscribe(sub);

    assertEquals(1, sub.chunks.size());
    assertInstanceOf(ModelChunk.MessageStop.class, sub.chunks.get(0));
    assertTrue(sub.completed.get());
  }

  @Test
  void nullContentSkipsTextDelta() {
    Model m =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            return Response.newBuilder()
                .withFinishReason(FinishReason.STOP)
                .withUsage(Response.Usage.of(1, 1))
                .build();
          }

          @Override
          public String id() {
            return "test";
          }

          @Override
          public String provider() {
            return "test";
          }
        };
    var sub = new CapturingSubscriber();
    m.chatStream(List.of(), List.of(), new CancellationToken()).subscribe(sub);
    assertEquals(1, sub.chunks.size());
    assertInstanceOf(ModelChunk.MessageStop.class, sub.chunks.get(0));
  }

  @Test
  void toolCallsExpandToStartStopPairs() {
    var c1 = new ToolCall("call-1", "read", Map.of("path", "/x"));
    var c2 = new ToolCall("call-2", "grep", Map.of("pattern", "y"));
    var sub = new CapturingSubscriber();
    withToolCalls("running tools", List.of(c1, c2))
        .chatStream(List.of(), List.of(), new CancellationToken())
        .subscribe(sub);

    assertEquals(6, sub.chunks.size());
    assertInstanceOf(ModelChunk.TextDelta.class, sub.chunks.get(0));
    var start1 = assertInstanceOf(ModelChunk.ToolUseStart.class, sub.chunks.get(1));
    assertEquals("call-1", start1.callId());
    assertEquals("read", start1.toolName());
    var stop1 = assertInstanceOf(ModelChunk.ToolUseStop.class, sub.chunks.get(2));
    assertEquals(c1, stop1.toolCall());
    var start2 = assertInstanceOf(ModelChunk.ToolUseStart.class, sub.chunks.get(3));
    assertEquals("call-2", start2.callId());
    var stop2 = assertInstanceOf(ModelChunk.ToolUseStop.class, sub.chunks.get(4));
    assertEquals(c2, stop2.toolCall());
    var msg = assertInstanceOf(ModelChunk.MessageStop.class, sub.chunks.get(5));
    assertEquals("TOOL_CALLS", msg.stopReason());
  }

  @Test
  void missingFinishReasonFallsBackToStop() {
    Model m =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            return Response.newBuilder().withContent("x").build();
          }

          @Override
          public String id() {
            return "test";
          }

          @Override
          public String provider() {
            return "test";
          }
        };
    var sub = new CapturingSubscriber();
    m.chatStream(List.of(), List.of(), new CancellationToken()).subscribe(sub);

    var stop =
        assertInstanceOf(ModelChunk.MessageStop.class, sub.chunks.get(sub.chunks.size() - 1));
    assertEquals("STOP", stop.stopReason());
    assertEquals(0, stop.usage().inputTokens());
    assertEquals(0, stop.usage().outputTokens());
  }

  @Test
  void preCancelledTokenFiresOnErrorOnFirstRequest() {
    var token = new CancellationToken();
    token.cancel("user-stop");
    var sub = new CapturingSubscriber();
    textOnly("ignored").chatStream(List.of(), List.of(), token).subscribe(sub);

    assertTrue(sub.chunks.isEmpty(), "no chunks delivered when pre-cancelled");
    assertFalse(sub.completed.get());
    var err = sub.error.get();
    assertInstanceOf(CancellationException.class, err);
    assertEquals("user-stop", err.getMessage());
  }

  @Test
  void nullCancellationTokenRejected() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> textOnly("x").chatStream(List.of(), List.of(), null));
    assertEquals("cancellation must not be null", ex.getMessage());
  }

  @Test
  void zeroRequestSurfacesIllegalArgument() {
    var sub = new CapturingSubscriber();
    sub.initialRequest = 0;
    textOnly("x").chatStream(List.of(), List.of(), new CancellationToken()).subscribe(sub);
    sub.subscription.request(0);
    assertInstanceOf(IllegalArgumentException.class, sub.error.get());
  }

  @Test
  void negativeRequestSurfacesIllegalArgument() {
    var sub = new CapturingSubscriber();
    sub.initialRequest = 0;
    textOnly("x").chatStream(List.of(), List.of(), new CancellationToken()).subscribe(sub);
    sub.subscription.request(-3);
    assertInstanceOf(IllegalArgumentException.class, sub.error.get());
  }

  @Test
  void subscriberCancelStopsFurtherEmission() {
    var sub =
        new CapturingSubscriber() {
          @Override
          public void onSubscribe(Flow.Subscription s) {
            this.subscription = s;
            s.request(1);
            s.cancel();
            s.request(10);
          }
        };
    var c1 = new ToolCall("a", "t", Map.of());
    var c2 = new ToolCall("b", "t", Map.of());
    withToolCalls("hi", List.of(c1, c2))
        .chatStream(List.of(), List.of(), new CancellationToken())
        .subscribe(sub);

    assertEquals(1, sub.chunks.size(), "only the first requested chunk is emitted");
    assertFalse(sub.completed.get());
    assertNull(sub.error.get());
  }

  @Test
  void multipleRequestsAccumulateAndComplete() {
    var sub =
        new CapturingSubscriber() {
          @Override
          public void onSubscribe(Flow.Subscription s) {
            this.subscription = s;
            s.request(1);
            s.request(1);
            s.request(10);
          }
        };
    textOnly("hello").chatStream(List.of(), List.of(), new CancellationToken()).subscribe(sub);
    assertEquals(2, sub.chunks.size());
    assertTrue(sub.completed.get());
  }

  @Test
  void onSubscribeNullRejected() {
    var publisher = textOnly("x").chatStream(List.of(), List.of(), new CancellationToken());
    assertThrows(NullPointerException.class, () -> publisher.subscribe(null));
  }

  @Test
  void subscriptionRemainsLiveAfterCompletionForIdempotentRequests() {
    var sub = new CapturingSubscriber();
    textOnly("hello").chatStream(List.of(), List.of(), new CancellationToken()).subscribe(sub);
    assertTrue(sub.completed.get());
    var before = sub.chunks.size();
    sub.subscription.request(5);
    assertEquals(before, sub.chunks.size(), "post-completion request must be a no-op");
  }
}
