/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.CostCalculator;
import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.test.MockModel;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.SteeringQueue;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

final class UsageAccountingTest {

  private static final Instant STARTED = Instant.parse("2026-10-29T10:00:00Z");
  private static final Instant ENDED = STARTED.plusMillis(1500);
  private static final String SECRET = "sk-accounting-secret-0001";

  private final LoopFixture fixture = new LoopFixture("sess-accounting", ENDED);
  private final MockModel model = new MockModel("unused");
  private final List<String> pricedIds = new CopyOnWriteArrayList<>();
  private final CostCalculator costs =
      (modelId, usage) -> {
        pricedIds.add(modelId);
        return CostEstimate.ofMicroUsd(usage.totalTokens());
      };
  private final SessionState state = fixture.state();

  private UsageAccounting accounting() {
    var secrets = new SecretRegistry();
    secrets.register("key", SECRET);
    var collaborators =
        fixture.collaborators(
            HookRegistry.empty(),
            fixture.dispatch(ToolRegistry.empty(), secrets),
            new SteeringQueue(1));
    return new UsageAccounting(collaborators, collaborators.emitter(), model, costs);
  }

  private QueryEvent.ModelCallEnded onlyEvent() {
    assertEquals(1, fixture.events.size());
    return (QueryEvent.ModelCallEnded) fixture.events.getFirst();
  }

  @Test
  void aTurnIsAccountedAtTheMainModelAndReportedWithExactlyThatUsageAndCost() {
    var turn = new TurnOutcome(FinishReason.STOP, "answer", Usage.of(100, 10));

    accounting().accountTurn(state, turn, 2, STARTED, ENDED);

    var event = onlyEvent();
    assertEquals(Usage.of(100, 10), state.totals().usage());
    assertEquals(CostEstimate.ofMicroUsd(110), state.totals().cost());
    assertEquals(state.totals().usage(), event.usage());
    assertEquals(state.totals().cost(), event.cost());
    assertEquals("mock", event.modelId());
    assertEquals(2, event.attempt());
    assertEquals(FinishReason.STOP, event.finishReason());
    assertEquals(Duration.ofMillis(1500), event.duration());
    assertEquals(ENDED, event.timestamp());
    assertEquals(state.runId(), event.runId());
    assertNull(event.error());
  }

  @Test
  void aRetriedAttemptIsReportedWithZeroUsageAndItsRedactedErrorAndIsNotAccounted() {
    accounting()
        .reportRetried(state, 1, new IllegalStateException("reset " + SECRET), STARTED, ENDED);

    var event = onlyEvent();
    assertEquals(Usage.of(0, 0), event.usage());
    assertEquals(CostEstimate.zero(), event.cost());
    assertEquals(FinishReason.ERROR, event.finishReason());
    assertEquals(IllegalStateException.class.getName(), event.error().kind());
    assertFalse(event.error().message().contains(SECRET));
    assertTrue(event.error().message().contains("<redacted:key>"));
    assertEquals(Usage.of(0, 0), state.totals().usage());
    assertTrue(pricedIds.isEmpty());
  }

  @Test
  void aCompactionIsAccountedAtItsOwnModelAsAStoppedFirstAttempt() {
    accounting().accountCompaction(state, "summary-model", Usage.of(0, 0, 0, 900), STARTED, ENDED);

    var event = onlyEvent();
    assertEquals(List.of("summary-model"), pricedIds);
    assertEquals("summary-model", event.modelId());
    assertEquals(1, event.attempt());
    assertEquals(FinishReason.STOP, event.finishReason());
    assertEquals(CostEstimate.ofMicroUsd(900), state.totals().cost());
  }
}
