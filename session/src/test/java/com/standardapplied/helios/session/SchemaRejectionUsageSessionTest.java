/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.common.HttpClientFactory;
import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.ProviderException;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.provider.StreamingModel;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.schema.StructuredContentParser;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.session.store.MessageOrigin;
import com.standardapplied.helios.session.test.EventCollector;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * A structured answer that fails its schema is billed in full: read through a provider's chunk
 * stream ({@link StreamingModel}), the rejected answer's usage is its model call's and counts in
 * the run, as the corrected answer's does, while the rejected attempt and its correction join the
 * history as before.
 */
final class SchemaRejectionUsageSessionTest {

  /** The session's typed answer. */
  public record Answer(String answer) {}

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Usage REJECTED = Usage.of(1_000, 200);
  private static final Usage ACCEPTED = Usage.of(1_300, 30);

  private final ConcurrentLinkedQueue<List<StreamEvent>> replies =
      new ConcurrentLinkedQueue<>(
          List.of(answer("not json", REJECTED), answer("{\"answer\":\"fixed\"}", ACCEPTED)));
  private final List<List<Message>> requests = new CopyOnWriteArrayList<>();

  @Test
  void aRejectedAnswerAndItsCorrectionAreEachAccountedWithTheUsageTheyReported() {
    var events = new EventCollector();
    ResultMessage result;
    try (var model = model();
        var session =
            AgentSession.open(
                SessionOptions.newBuilder()
                    .withModel(model)
                    .withOutputSchema(OutputSchema.of(Answer.class))
                    .withCostCalculator(
                        (modelId, usage) -> CostEstimate.ofMicroUsd(usage.totalTokens()))
                    .build())) {
      events.read(session.events());
      result = Await.value("the run", session.send("go"));
    }
    events.awaitDone();

    assertInstanceOf(ResultMessage.Success.class, result);
    assertEquals(REJECTED.plus(ACCEPTED), result.usage());
    assertEquals(CostEstimate.ofMicroUsd(1_200 + 1_330), result.cost());
    assertEquals(
        List.of(List.of(FinishReason.ERROR, REJECTED), List.of(FinishReason.STOP, ACCEPTED)),
        events.eventsOf(QueryEvent.ModelCallEnded.class).stream()
            .map(call -> List.<Object>of(call.finishReason(), call.usage()))
            .toList());
    assertEquals(
        List.of(
            Optional.empty(),
            Optional.of(MessageOrigin.REJECTED_ATTEMPT),
            Optional.of(MessageOrigin.SCHEMA_CORRECTION)),
        requests.getLast().stream().map(MessageOrigin::of).toList());
  }

  /** A provider's model answering each request with the next reply's events. */
  private StreamingModel<List<Message>> model() {
    return StreamingModel.<List<Message>>newBuilder()
        .withId("metered")
        .withProvider("scripted")
        .withConfig(ModelConfig.newBuilder().build())
        .withHttpClient(HttpClientFactory.create())
        .withRequests(
            (messages, tools, schema) -> {
              requests.add(messages);
              return messages;
            })
        .withExchange(request -> CloseableIterator.of(replies.remove().iterator()))
        .withFailure(ProviderException::new)
        .withJson(
            new StructuredContentParser.JsonAdapter(
                JSON.readerFor(Map.class)::readValue, JSON::convertValue))
        .build();
  }

  /** The events of a stream answering {@code text} that reports {@code usage}. */
  private static List<StreamEvent> answer(String text, Usage usage) {
    return List.of(
        new StreamEvent.TextDelta(text),
        new StreamEvent.Done(
            Response.newBuilder()
                .withContent(text)
                .withFinishReason(FinishReason.STOP)
                .withUsage(usage)
                .build()));
  }
}
