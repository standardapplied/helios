/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.CostEstimate;
import ai.singlr.core.model.Citation;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Response.Usage;
import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.session.ResultMessage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class SessionStateTest {

  private static final String SID = "sess-1";

  private static SessionState build() {
    return new SessionState(SID, new CancellationToken(), Clock.systemUTC());
  }

  private static SessionState buildAt(Instant fixed) {
    return new SessionState(SID, new CancellationToken(), Clock.fixed(fixed, ZoneOffset.UTC));
  }

  @Test
  void accessorsReturnConstructorValues() {
    var cancellation = new CancellationToken();
    var fixed = Instant.parse("2026-05-14T19:00:00Z");
    var state = new SessionState(SID, cancellation, Clock.fixed(fixed, ZoneOffset.UTC));
    assertEquals(SID, state.sessionId());
    assertSame(cancellation, state.cancellation());
    assertEquals(fixed, state.startedAt());
    assertEquals(0, state.currentTurnIndex());
    assertEquals(Usage.of(0, 0), state.usage());
    assertEquals(CostEstimate.zero(), state.cost());
    assertTrue(state.historySnapshot().isEmpty());
    assertFalse(state.isTerminal());
    assertEquals(Optional.empty(), state.terminal());
  }

  @Test
  void nullSessionIdRejected() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new SessionState(null, new CancellationToken(), Clock.systemUTC()));
    assertEquals("sessionId must not be null", ex.getMessage());
  }

  @Test
  void blankSessionIdRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new SessionState("  ", new CancellationToken(), Clock.systemUTC()));
    assertEquals("sessionId must not be blank", ex.getMessage());
  }

  @Test
  void nullCancellationRejected() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> new SessionState(SID, null, Clock.systemUTC()));
    assertEquals("cancellation must not be null", ex.getMessage());
  }

  @Test
  void nullClockRejected() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> new SessionState(SID, new CancellationToken(), null));
    assertEquals("clock must not be null", ex.getMessage());
  }

  @Test
  void beginTurnIncrementsAndReturnsNewIndex() {
    var state = build();
    assertEquals(1, state.beginTurn());
    assertEquals(2, state.beginTurn());
    assertEquals(2, state.currentTurnIndex());
  }

  @Test
  void appendMessageGrowsHistorySnapshot() {
    var state = build();
    state.appendMessage(Message.user("hello"));
    state.appendMessage(Message.assistant("hi"));
    var snapshot = state.historySnapshot();
    assertEquals(2, snapshot.size());
    assertEquals("hello", snapshot.get(0).content());
    assertEquals("hi", snapshot.get(1).content());
  }

  @Test
  void historySnapshotIsImmutable() {
    var state = build();
    state.appendMessage(Message.user("hello"));
    var snapshot = state.historySnapshot();
    assertThrows(UnsupportedOperationException.class, () -> snapshot.add(Message.user("bad")));
  }

  @Test
  void historySnapshotIsFreshOnEachCall() {
    var state = build();
    state.appendMessage(Message.user("first"));
    var snap1 = state.historySnapshot();
    state.appendMessage(Message.user("second"));
    var snap2 = state.historySnapshot();
    assertEquals(1, snap1.size());
    assertEquals(2, snap2.size());
  }

  @Test
  void appendMessageRejectsNull() {
    var state = build();
    var ex = assertThrows(NullPointerException.class, () -> state.appendMessage(null));
    assertEquals("message must not be null", ex.getMessage());
  }

  @Test
  void accumulateUsageSumsAllFieldsIncludingTotal() {
    var state = build();
    state.accumulateUsage(Usage.of(10, 5));
    state.accumulateUsage(Usage.of(7, 3));
    var sum = state.usage();
    assertEquals(17, sum.inputTokens());
    assertEquals(8, sum.outputTokens());
    assertEquals(25, sum.totalTokens());
  }

  @Test
  void accumulateUsagePreservesExplicitTotal() {
    var state = build();
    state.accumulateUsage(new Usage(2, 3, 0, 0, 99));
    assertEquals(99, state.usage().totalTokens(), "explicit total carries through");
  }

  @Test
  void accumulateUsageRejectsNull() {
    var state = build();
    var ex = assertThrows(NullPointerException.class, () -> state.accumulateUsage(null));
    assertEquals("delta must not be null", ex.getMessage());
  }

  @Test
  void accumulateCostAccumulates() {
    var state = build();
    state.accumulateCost(CostEstimate.ofMicroUsd(250_000L));
    state.accumulateCost(CostEstimate.ofMicroUsd(100_000L));
    assertEquals(350_000L, state.cost().microUsd());
  }

  @Test
  void accumulateCostRejectsNull() {
    var state = build();
    var ex = assertThrows(NullPointerException.class, () -> state.accumulateCost(null));
    assertEquals("delta must not be null", ex.getMessage());
  }

  @Test
  void citationsStartEmpty() {
    assertTrue(build().citations().isEmpty());
  }

  @Test
  void accumulateCitationsAppendsInOrderAcrossTurns() {
    var state = build();
    var a = Citation.of("https://a", "x");
    var b = Citation.of("https://b", "y");
    var c = Citation.of("https://c", "z");
    state.accumulateCitations(List.of(a, b));
    state.accumulateCitations(List.of(c));
    assertEquals(List.of(a, b, c), state.citations());
  }

  @Test
  void accumulateCitationsSuppressesExactDuplicates() {
    var state = build();
    var a = Citation.of("https://a", "x");
    var b = Citation.of("https://b", "y");
    state.accumulateCitations(List.of(a, b));
    state.accumulateCitations(List.of(a, Citation.of("https://b", "y")));
    assertEquals(List.of(a, b), state.citations());
  }

  @Test
  void accumulateCitationsEmptyDeltaIsNoOp() {
    var state = build();
    state.accumulateCitations(List.of());
    assertTrue(state.citations().isEmpty());
  }

  @Test
  void citationsSnapshotIsImmutable() {
    var state = build();
    state.accumulateCitations(List.of(Citation.of("https://a", "x")));
    assertThrows(
        UnsupportedOperationException.class,
        () -> state.citations().add(Citation.of("https://b", "y")));
  }

  @Test
  void accumulateCitationsRejectsNullDelta() {
    var ex = assertThrows(NullPointerException.class, () -> build().accumulateCitations(null));
    assertEquals("delta must not be null", ex.getMessage());
  }

  @Test
  void accumulateCitationsRejectsNullElement() {
    var withNull = new java.util.ArrayList<Citation>();
    withNull.add(null);
    var ex = assertThrows(NullPointerException.class, () -> build().accumulateCitations(withNull));
    assertEquals("delta must not contain null", ex.getMessage());
  }

  @Test
  void elapsedIsZeroForFixedClock() {
    var state = buildAt(Instant.parse("2026-05-14T19:00:00Z"));
    assertEquals(Duration.ZERO, state.elapsed());
  }

  @Test
  void elapsedReflectsClockMovement() {
    var t0 = Instant.parse("2026-05-14T19:00:00Z");
    var movingClock =
        new Clock() {
          int calls = 0;

          @Override
          public ZoneOffset getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(java.time.ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return calls++ == 0 ? t0 : t0.plusSeconds(7);
          }
        };
    var state = new SessionState(SID, new CancellationToken(), movingClock);
    assertEquals(Duration.ofSeconds(7), state.elapsed());
  }

  @Test
  void setTerminalIsFirstWins() {
    var state = build();
    var first =
        new ResultMessage.Success(SID, "ok", Usage.of(0, 0), CostEstimate.zero(), Duration.ZERO);
    var second =
        new ResultMessage.Cancelled(
            SID, "user-stop", Usage.of(0, 0), CostEstimate.zero(), Duration.ZERO);
    assertTrue(state.setTerminal(first));
    assertFalse(state.setTerminal(second));
    assertTrue(state.isTerminal());
    assertEquals(Optional.of(first), state.terminal());
  }

  @Test
  void setTerminalRejectsNull() {
    var state = build();
    var ex = assertThrows(NullPointerException.class, () -> state.setTerminal(null));
    assertEquals("result must not be null", ex.getMessage());
  }

  @Test
  void contextWarningFlagStartsClear() {
    var state = build();
    assertFalse(state.contextWarningFired());
  }

  @Test
  void tryFireContextWarningIsFirstWins() {
    var state = build();
    assertTrue(state.tryFireContextWarning());
    assertTrue(state.contextWarningFired());
    assertFalse(state.tryFireContextWarning(), "second call must return false");
    assertTrue(state.contextWarningFired());
  }

  @Test
  void resetContextWarningFlagReArms() {
    var state = build();
    state.tryFireContextWarning();
    state.resetContextWarningFlag();
    assertFalse(state.contextWarningFired());
    assertTrue(state.tryFireContextWarning(), "post-reset try must succeed again");
  }
}
