/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.ModelChunk;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.Response.Usage;
import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.schema.StructuredOutputParseException;
import ai.singlr.core.tool.Tool;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for the session-loop schema self-correction fix landed in 2.3.3.
 *
 * <p>Pre-2.3.3 the session terminated as {@link ResultMessage.ErrorDuringExecution} on the first
 * {@link StructuredOutputParseException} (CLAUDE.md's "Structured Output Resilience" row was
 * aspirational). Now the loop appends the model's wrong attempt to history, injects a corrective
 * synthetic user message naming each schema-validator error, and iterates until the model converges
 * (bounded by {@link SessionLimits#maxTurns()}).
 */
class SchemaParseSelfCorrectionReproTest {

  public record Sample(String field) {}

  private static final String SID = "sess-schema-self-correct";
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-05-23T00:00:00Z"), ZoneOffset.UTC);

  /**
   * Model whose first typed turn throws {@link StructuredOutputParseException} (mirroring a real
   * provider that received parseable JSON which didn't match the schema). Subsequent turns return a
   * valid Sample so a self-correcting loop converges on the second iteration.
   */
  private static final class FlakyThenCleanModel implements Model {
    final AtomicInteger calls = new AtomicInteger(0);

    @Override
    public Response<Void> chat(List<Message> messages, List<Tool> tools) {
      return Response.newBuilder().withContent("{\"field\":\"plain\"}").build();
    }

    @Override
    public Flow.Publisher<ModelChunk> chatStream(
        List<Message> messages,
        List<Tool> tools,
        OutputSchema<?> outputSchema,
        CancellationToken cancellation) {
      var attempt = calls.incrementAndGet();
      if (attempt == 1) {
        return subscriber ->
            subscriber.onSubscribe(
                new Flow.Subscription() {
                  @Override
                  public void request(long n) {
                    subscriber.onError(
                        new StructuredOutputParseException(
                            List.of("field is required but missing"), "{\"wrong\":\"shape\"}"));
                  }

                  @Override
                  public void cancel() {}
                });
      }
      return one("{\"field\":\"recovered\"}");
    }

    private static Flow.Publisher<ModelChunk> one(String content) {
      return subscriber ->
          subscriber.onSubscribe(
              new Flow.Subscription() {
                int i = 0;

                @Override
                public void request(long n) {
                  if (i == 0) {
                    subscriber.onNext(new ModelChunk.TextDelta(content));
                    i = 1;
                  }
                  if (i == 1) {
                    subscriber.onNext(
                        new ModelChunk.MessageStop(
                            FinishReason.STOP.name(), Usage.of(1, 1), Map.of()));
                    i = 2;
                    subscriber.onComplete();
                  }
                }

                @Override
                public void cancel() {}
              });
    }

    @Override
    public String id() {
      return "flaky-then-clean";
    }

    @Override
    public String provider() {
      return "test";
    }
  }

  @Test
  void sessionSelfCorrectsOnStructuredOutputParseExceptionAndConvergesOnSecondTurn() {
    var schema = OutputSchema.of(Sample.class);
    var model = new FlakyThenCleanModel();
    try (var session =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(model)
                .withSessionId(SID)
                .withClock(CLOCK)
                .withOutputSchema(schema)
                .build())) {
      var terminal = session.runBlocking(UserMessage.text("go"));
      var success = assertInstanceOf(ResultMessage.Success.class, terminal);
      assertTrue(
          success.result().contains("recovered"),
          "second-turn clean JSON must surface as the terminal result: " + success.result());
      assertEquals(2, model.calls.get(), "model was called twice: first errored, second clean");
    }
  }

  /**
   * Model that <i>never</i> produces a clean response. Validates that self-correction is bounded by
   * the existing {@code maxTurns} ceiling — the session eventually terminates as {@link
   * ResultMessage.ErrorMaxTurns} instead of looping forever.
   */
  private static final class AlwaysParseFailureModel implements Model {
    final AtomicInteger calls = new AtomicInteger(0);

    @Override
    public Response<Void> chat(List<Message> messages, List<Tool> tools) {
      return Response.newBuilder().build();
    }

    @Override
    public Flow.Publisher<ModelChunk> chatStream(
        List<Message> messages,
        List<Tool> tools,
        OutputSchema<?> outputSchema,
        CancellationToken cancellation) {
      calls.incrementAndGet();
      return subscriber ->
          subscriber.onSubscribe(
              new Flow.Subscription() {
                @Override
                public void request(long n) {
                  subscriber.onError(
                      new StructuredOutputParseException(
                          List.of("field is required but missing"),
                          "{\"wrong\":\"shape-" + calls.get() + "\"}"));
                }

                @Override
                public void cancel() {}
              });
    }

    @Override
    public String id() {
      return "always-parse-fail";
    }

    @Override
    public String provider() {
      return "test";
    }
  }

  @Test
  void persistentParseFailureTerminatesAtMaxTurnsCeiling() {
    var schema = OutputSchema.of(Sample.class);
    var model = new AlwaysParseFailureModel();
    try (var session =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(model)
                .withSessionId(SID)
                .withClock(CLOCK)
                .withOutputSchema(schema)
                .withLimits(SessionLimits.newBuilder().withMaxTurns(3).build())
                .build())) {
      var terminal = session.runBlocking(UserMessage.text("go"));
      assertInstanceOf(ResultMessage.ErrorMaxTurns.class, terminal);
      assertEquals(3, model.calls.get(), "model retried up to maxTurns then terminated");
    }
  }

  /**
   * Streaming-provider variant — the model emits some text deltas before the parse error fires and
   * the exception itself carries no {@code rawContent}. TurnRunner must fall back to the
   * subscriber's accumulated content so the assistant message in history still reflects what the
   * model attempted.
   */
  private static final class DeltaThenErrorModel implements Model {
    final AtomicInteger calls = new AtomicInteger(0);

    @Override
    public Response<Void> chat(List<Message> messages, List<Tool> tools) {
      return Response.newBuilder().build();
    }

    @Override
    public Flow.Publisher<ModelChunk> chatStream(
        List<Message> messages,
        List<Tool> tools,
        OutputSchema<?> outputSchema,
        CancellationToken cancellation) {
      var attempt = calls.incrementAndGet();
      if (attempt == 1) {
        return subscriber ->
            subscriber.onSubscribe(
                new Flow.Subscription() {
                  int step = 0;

                  @Override
                  public void request(long n) {
                    if (step == 0) {
                      subscriber.onNext(new ModelChunk.TextDelta("{\"wrong\":\"shape\"}"));
                      step = 1;
                    }
                    if (step == 1) {
                      subscriber.onError(
                          new StructuredOutputParseException(
                              List.of("field is required but missing"), null));
                      step = 2;
                    }
                  }

                  @Override
                  public void cancel() {}
                });
      }
      return subscriber ->
          subscriber.onSubscribe(
              new Flow.Subscription() {
                int i = 0;

                @Override
                public void request(long n) {
                  if (i == 0) {
                    subscriber.onNext(new ModelChunk.TextDelta("{\"field\":\"recovered\"}"));
                    i = 1;
                  }
                  if (i == 1) {
                    subscriber.onNext(
                        new ModelChunk.MessageStop(
                            FinishReason.STOP.name(), Usage.of(1, 1), Map.of()));
                    i = 2;
                    subscriber.onComplete();
                  }
                }

                @Override
                public void cancel() {}
              });
    }

    @Override
    public String id() {
      return "delta-then-error";
    }

    @Override
    public String provider() {
      return "test";
    }
  }

  @Test
  void streamingProviderWithNullRawContentFallsBackToSubscriberAccumulatedText() {
    var schema = OutputSchema.of(Sample.class);
    var model = new DeltaThenErrorModel();
    try (var session =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(model)
                .withSessionId(SID)
                .withClock(CLOCK)
                .withOutputSchema(schema)
                .build())) {
      var terminal = session.runBlocking(UserMessage.text("go"));
      var success = assertInstanceOf(ResultMessage.Success.class, terminal);
      assertTrue(success.result().contains("recovered"));
      assertEquals(2, model.calls.get());
    }
  }
}
