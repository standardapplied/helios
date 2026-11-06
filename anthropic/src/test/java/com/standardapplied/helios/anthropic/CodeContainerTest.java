/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.anthropic.api.AnthropicJson;
import com.standardapplied.helios.anthropic.api.ContentDelta;
import com.standardapplied.helios.anthropic.api.MessagesRequest;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.Role;
import com.standardapplied.helios.core.test.Golden;
import com.standardapplied.helios.core.test.ModelHarness;
import java.net.URI;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The code execution container a turn ran in goes back on the requests after it. The recording is
 * {@code dynamic-filtering-loop.sse}, a live claude-sonnet-5-5 turn and its answer recorded
 * 2026-11-01: the first response ran code in {@link #CONTAINER}, the second reports none.
 */
class CodeContainerTest {

  private static final String RECORDING = "anthropic/streams/dynamic-filtering-loop.sse";

  private static final String CONTAINER = "container_011DQc6c81fX5Zn7TEdritvr";

  private static final Instant EXPIRES_AT = Instant.parse("2026-11-01T21:01:11.250203Z");

  private static final ModelConfig WEB_SEARCH =
      ModelConfig.newBuilder().withApiKey("k").withWebSearch(true).build();

  private static List<String> replies() {
    return new ArrayList<>(Arrays.asList(Golden.read(RECORDING).split(ModelHarness.NEXT_RESPONSE)));
  }

  private static Response<?> recorded(String reply) {
    return AnthropicFixture.drainSseFixture(reply).response();
  }

  /** The recorded conversation up to the request after its first response. */
  private static List<Message> afterTheRecordedTurn() {
    var turn = recorded(replies().getFirst());
    var history = new ArrayList<>(List.of(Message.user("Find the year.")));
    history.add(turn.toMessage());
    turn.toolCalls().forEach(c -> history.add(Message.tool(c.id(), c.name(), "saved")));
    return history;
  }

  private static MessagesRequest nextRequest(
      AnthropicModelId model, ModelConfig config, Instant now, List<Message> history) {
    return new AnthropicRequestBuilder(
            model.id(), model, config, CachePolicy.shortLived(), InstantSource.fixed(now))
        .build(history, List.of(), null)
        .body();
  }

  private static MessagesRequest nextRequest(Instant now, List<Message> history) {
    return nextRequest(AnthropicModelId.CLAUDE_SONNET_5_5, WEB_SEARCH, now, history);
  }

  private static Message assistant(String container, Instant expiresAt) {
    return Message.assistant(
        "ran code",
        List.of(),
        Map.of(
            CodeContainer.ID_KEY, container, CodeContainer.EXPIRES_AT_KEY, expiresAt.toString()));
  }

  private static Model dynamicFilteringModel(URI uri) {
    return new AnthropicProvider()
        .create(
            AnthropicModelId.CLAUDE_SONNET_5_5.id(),
            ModelConfig.newBuilder()
                .withApiKey("test-key")
                .withBaseUrl(uri + "/v1/messages")
                .withWebSearch(true)
                .build());
  }

  @Test
  void aTurnRecordsTheContainerItRanCodeIn() {
    var metadata = recorded(replies().getFirst()).metadata();

    assertEquals(CONTAINER, metadata.get(CodeContainer.ID_KEY));
    assertEquals(EXPIRES_AT.toString(), metadata.get(CodeContainer.EXPIRES_AT_KEY));
  }

  @Test
  void aTurnThatRanNoCodeRecordsNoContainer() {
    var metadata = recorded(replies().getLast()).metadata();

    assertFalse(metadata.containsKey(CodeContainer.ID_KEY));
    assertFalse(metadata.containsKey(CodeContainer.EXPIRES_AT_KEY));
  }

  @Test
  void aContainerWithoutItsIdOrExpiryIsNotRecorded() {
    var metadata = new HashMap<String, String>();

    CodeContainer.record(metadata, null);
    CodeContainer.record(metadata, new ContentDelta.Container(null, EXPIRES_AT));
    CodeContainer.record(metadata, new ContentDelta.Container(CONTAINER, null));

    assertEquals(Map.of(), metadata);
  }

  @Test
  void theNextTurnRunsInTheContainerUntilItsExpiry() {
    var request = nextRequest(EXPIRES_AT.minusNanos(1), afterTheRecordedTurn());

    assertEquals(CONTAINER, request.container());
    assertTrue(
        AnthropicJson.LENIENT
            .writeValueAsString(request)
            .contains("\"container\":\"" + CONTAINER + "\""));
  }

  @Test
  void theNextTurnStartsANewContainerOnceItsExpiryIsReached() {
    var request = nextRequest(EXPIRES_AT, afterTheRecordedTurn());

    assertNull(request.container());
    assertFalse(AnthropicJson.LENIENT.writeValueAsString(request).contains("\"container\""));
  }

  @Test
  void aRequestWithoutWebSearchSendsNoContainer() {
    var withoutSearch = ModelConfig.newBuilder().withApiKey("k").build();

    var request =
        nextRequest(
            AnthropicModelId.CLAUDE_SONNET_5_5,
            withoutSearch,
            EXPIRES_AT.minusNanos(1),
            afterTheRecordedTurn());

    assertNull(request.container());
  }

  @Test
  void theLatestAssistantTurnThatRecordedAContainerNamesIt() {
    var later = EXPIRES_AT.plusSeconds(3600);
    var userWithContainer =
        new Message(
            Role.USER,
            "hi",
            List.of(),
            null,
            null,
            Map.of(CodeContainer.ID_KEY, "user", CodeContainer.EXPIRES_AT_KEY, later.toString()),
            List.of());
    var withoutMetadata =
        new Message(Role.ASSISTANT, "no code", List.of(), null, null, null, List.of());
    var history =
        List.of(
            Message.user("first"),
            assistant("older", later),
            assistant("latest", later),
            userWithContainer,
            withoutMetadata,
            Message.assistant("no code"));

    assertEquals("latest", nextRequest(EXPIRES_AT, history).container());
  }

  @Test
  void anExpiredLatestContainerIsNotReplacedByAnEarlierOne() {
    var history =
        List.of(
            Message.user("first"),
            assistant("older", EXPIRES_AT.plusSeconds(3600)),
            assistant("latest", EXPIRES_AT));

    assertNull(nextRequest(EXPIRES_AT, history).container());
  }

  @Test
  void aConversationWithoutAContainerSendsNone() {
    assertNull(nextRequest(EXPIRES_AT, List.of(Message.user("first"))).container());
  }

  @Test
  void aPausedTurnResumesInTheContainerItRanCodeIn() {
    var replies = replies();
    replies.set(
        0,
        replies
            .getFirst()
            .replace("\"stop_reason\":\"tool_use\"", "\"stop_reason\":\"pause_turn\""));

    var requests =
        ModelHarness.exchange(
            replies,
            CodeContainerTest::dynamicFilteringModel,
            model -> model.chat(List.of(Message.user("Find the year.")), List.of()));

    assertFalse(requests.get(0).body().contains("\"container\""));
    assertTrue(requests.get(1).body().contains("\"container\":\"" + CONTAINER + "\""));
  }

  @Test
  void aContinuationKeepsTheRequestsContainerWhenThePausedSegmentRanNoCode() {
    var request = MessagesRequest.newBuilder().withContainer("earlier").build();

    assertEquals("earlier", request.continuationWith(List.of(), null).container());
    assertEquals("paused", request.continuationWith(List.of(), "paused").container());
  }

  @Test
  void aMergedTurnKeepsTheLaterSegmentsContainerElseTheEarlierOnes() {
    var earlier = segment(Map.of(CodeContainer.ID_KEY, "a", CodeContainer.EXPIRES_AT_KEY, "1"));
    var later = segment(Map.of(CodeContainer.ID_KEY, "b", CodeContainer.EXPIRES_AT_KEY, "2"));
    var none = segment(Map.of());

    assertEquals(
        Map.of(CodeContainer.ID_KEY, "a", CodeContainer.EXPIRES_AT_KEY, "1"),
        SegmentMerge.merge(earlier, none).metadata());
    assertEquals(
        Map.of(CodeContainer.ID_KEY, "b", CodeContainer.EXPIRES_AT_KEY, "2"),
        SegmentMerge.merge(earlier, later).metadata());
    assertEquals(Map.of(), SegmentMerge.merge(none, none).metadata());
  }

  private static Response<Void> segment(Map<String, String> metadata) {
    return Response.newBuilder()
        .withContent("")
        .withFinishReason(FinishReason.STOP)
        .withMetadata(metadata)
        .build();
  }
}
