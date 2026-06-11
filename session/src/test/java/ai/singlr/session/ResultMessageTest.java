/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.CostEstimate;
import ai.singlr.core.model.Citation;
import ai.singlr.core.model.Response.Usage;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ResultMessageTest {

  private static final String SID = "sess-1";
  private static final Usage USAGE = Usage.of(100, 50);
  private static final CostEstimate COST = CostEstimate.ofUsd(0.0125);
  private static final Duration DUR = Duration.ofSeconds(7);

  // ── Subtype: Success ────────────────────────────────────────────────────────

  @Test
  void successConstructsAndExposesFields() {
    var r = new ResultMessage.Success(SID, "answer", USAGE, COST, DUR);
    assertEquals(SID, r.sessionId());
    assertEquals("answer", r.result());
    assertSame(USAGE, r.usage());
    assertSame(COST, r.cost());
    assertEquals(DUR, r.duration());
  }

  @Test
  void successConvenienceConstructorDefaultsCitationsToEmpty() {
    assertTrue(new ResultMessage.Success(SID, "answer", USAGE, COST, DUR).citations().isEmpty());
  }

  @Test
  void successCarriesAndDefensivelyCopiesCitations() {
    var cite = Citation.of("https://src", "snippet");
    var mutable = new java.util.ArrayList<Citation>();
    mutable.add(cite);
    var r = new ResultMessage.Success(SID, "answer", USAGE, COST, DUR, mutable);
    mutable.add(Citation.of("https://b", "y"));
    assertEquals(List.of(cite), r.citations());
    assertThrows(UnsupportedOperationException.class, () -> r.citations().add(cite));
  }

  @Test
  void successRejectsNullCitations() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new ResultMessage.Success(SID, "answer", USAGE, COST, DUR, null));
    assertEquals("citations must not be null", ex.getMessage());
  }

  @Test
  void nonSuccessTerminalsExposeEmptyCitationsByDefault() {
    ResultMessage maxTurns = new ResultMessage.ErrorMaxTurns(SID, 3, USAGE, COST, DUR);
    assertTrue(maxTurns.citations().isEmpty());
  }

  @Test
  void successRejectsNullResult() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new ResultMessage.Success(SID, null, USAGE, COST, DUR));
    assertEquals("result must not be null", ex.getMessage());
  }

  @Test
  void successAllowsEmptyResult() {
    // The empty assistant message is a legal terminal state (e.g. tool-only flow with no
    // final summary). Validation rejects null but not empty.
    var r = new ResultMessage.Success(SID, "", USAGE, COST, DUR);
    assertEquals("", r.result());
  }

  // ── Common-field validation (exercised through Success) ─────────────────────

  @Test
  void nullSessionIdRejected() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new ResultMessage.Success(null, "a", USAGE, COST, DUR));
    assertEquals("sessionId must not be null", ex.getMessage());
  }

  @Test
  void blankSessionIdRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new ResultMessage.Success("  ", "a", USAGE, COST, DUR));
    assertEquals("sessionId must not be blank", ex.getMessage());
  }

  @Test
  void nullUsageRejected() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> new ResultMessage.Success(SID, "a", null, COST, DUR));
    assertEquals("usage must not be null", ex.getMessage());
  }

  @Test
  void nullCostRejected() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new ResultMessage.Success(SID, "a", USAGE, null, DUR));
    assertEquals("cost must not be null", ex.getMessage());
  }

  @Test
  void nullDurationRejected() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new ResultMessage.Success(SID, "a", USAGE, COST, null));
    assertEquals("duration must not be null", ex.getMessage());
  }

  @Test
  void negativeDurationRejected() {
    var bad = Duration.ofSeconds(-1);
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new ResultMessage.Success(SID, "a", USAGE, COST, bad));
    assertTrue(ex.getMessage().startsWith("duration must not be negative"));
  }

  @Test
  void zeroDurationAllowed() {
    var r = new ResultMessage.Success(SID, "a", USAGE, COST, Duration.ZERO);
    assertEquals(Duration.ZERO, r.duration());
  }

  // ── Subtype: ErrorMaxTurns ──────────────────────────────────────────────────

  @Test
  void errorMaxTurnsConstructsAndExposesFields() {
    var r = new ResultMessage.ErrorMaxTurns(SID, 100, USAGE, COST, DUR);
    assertEquals(SID, r.sessionId());
    assertEquals(100, r.turnsUsed());
    assertSame(USAGE, r.usage());
    assertSame(COST, r.cost());
    assertEquals(DUR, r.duration());
  }

  @Test
  void errorMaxTurnsRejectsNegativeTurnsUsed() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new ResultMessage.ErrorMaxTurns(SID, -1, USAGE, COST, DUR));
    assertEquals("turnsUsed must be non-negative, got -1", ex.getMessage());
  }

  @Test
  void errorMaxTurnsAllowsZero() {
    var r = new ResultMessage.ErrorMaxTurns(SID, 0, USAGE, COST, DUR);
    assertEquals(0, r.turnsUsed());
  }

  // ── Subtype: ErrorMaxBudgetUsd ─────────────────────────────────────────────

  @Test
  void errorMaxBudgetUsdConstructsAndExposesFields() {
    var r = new ResultMessage.ErrorMaxBudgetUsd(SID, 5_000_000L, USAGE, COST, DUR);
    assertEquals(5_000_000L, r.microUsdSpent());
  }

  @Test
  void errorMaxBudgetUsdRejectsNegativeMicroUsdSpent() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new ResultMessage.ErrorMaxBudgetUsd(SID, -1L, USAGE, COST, DUR));
    assertEquals("microUsdSpent must be non-negative, got -1", ex.getMessage());
  }

  // ── Subtype: ErrorMaxWallClock ─────────────────────────────────────────────

  @Test
  void errorMaxWallClockConstructsAndExposesFields() {
    var r = new ResultMessage.ErrorMaxWallClock(SID, USAGE, COST, DUR);
    assertEquals(SID, r.sessionId());
    assertSame(USAGE, r.usage());
    assertSame(COST, r.cost());
    assertEquals(DUR, r.duration());
  }

  // ── Subtype: ErrorDuringExecution ──────────────────────────────────────────

  @Test
  void errorDuringExecutionConstructsAndExposesFields() {
    var err = SerializedError.of("hook-block", "blocked by validator");
    var r = new ResultMessage.ErrorDuringExecution(SID, err, USAGE, COST, DUR);
    assertSame(err, r.error());
  }

  @Test
  void errorDuringExecutionRejectsNullError() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new ResultMessage.ErrorDuringExecution(SID, null, USAGE, COST, DUR));
    assertEquals("error must not be null", ex.getMessage());
  }

  // ── Subtype: ErrorTransientStream ─────────────────────────────────────────

  @Test
  void errorTransientStreamConstructsAndExposesFields() {
    var err = SerializedError.of("kind", "msg");
    var r = new ResultMessage.ErrorTransientStream(SID, "anthropic", 3, err, USAGE, COST, DUR);
    assertEquals("anthropic", r.providerName());
    assertEquals(3, r.attemptsMade());
    assertSame(err, r.error());
  }

  @Test
  void errorTransientStreamRejectsNullProviderName() {
    var err = SerializedError.of("kind", "msg");
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new ResultMessage.ErrorTransientStream(SID, null, 1, err, USAGE, COST, DUR));
    assertEquals("providerName must not be null", ex.getMessage());
  }

  @Test
  void errorTransientStreamRejectsBlankProviderName() {
    var err = SerializedError.of("kind", "msg");
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new ResultMessage.ErrorTransientStream(SID, "  ", 1, err, USAGE, COST, DUR));
    assertEquals("providerName must not be blank", ex.getMessage());
  }

  @Test
  void errorTransientStreamRejectsZeroAttempts() {
    var err = SerializedError.of("kind", "msg");
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new ResultMessage.ErrorTransientStream(SID, "anthropic", 0, err, USAGE, COST, DUR));
    assertEquals("attemptsMade must be >= 1, got 0", ex.getMessage());
  }

  @Test
  void errorTransientStreamRejectsNullError() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new ResultMessage.ErrorTransientStream(
                    SID, "anthropic", 1, null, USAGE, COST, DUR));
    assertEquals("error must not be null", ex.getMessage());
  }

  @Test
  void errorTransientStreamWithoutStackTracesStripsFrames() {
    var carrying = SerializedError.of(new IllegalStateException("internal detail"));
    assertTrue(carrying.stackTrace().size() > 0, "test precondition");
    var terminal =
        new ResultMessage.ErrorTransientStream(SID, "anthropic", 2, carrying, USAGE, COST, DUR);
    var redacted = terminal.withoutStackTraces();
    var ets = (ResultMessage.ErrorTransientStream) redacted;
    assertEquals("anthropic", ets.providerName());
    assertEquals(2, ets.attemptsMade());
    assertEquals(List.of(), ets.error().stackTrace());
  }

  @Test
  void errorTransientStreamWithoutStackTracesIsNoOpWhenAlreadyClean() {
    var clean = SerializedError.of("kind", "msg");
    var terminal =
        new ResultMessage.ErrorTransientStream(SID, "anthropic", 1, clean, USAGE, COST, DUR);
    assertSame(terminal, terminal.withoutStackTraces());
  }

  // ── Subtype: Refusal ───────────────────────────────────────────────────────

  @Test
  void refusalConstructsAndExposesFields() {
    var r = new ResultMessage.Refusal(SID, "cannot help with that", USAGE, COST, DUR);
    assertEquals("cannot help with that", r.refusalText());
  }

  @Test
  void refusalRejectsNullText() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new ResultMessage.Refusal(SID, null, USAGE, COST, DUR));
    assertEquals("refusalText must not be null", ex.getMessage());
  }

  @Test
  void refusalRejectsBlankText() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new ResultMessage.Refusal(SID, "   ", USAGE, COST, DUR));
    assertEquals("refusalText must not be blank", ex.getMessage());
  }

  // ── Subtype: ErrorProviderUnavailable ─────────────────────────────────────

  @Test
  void errorProviderUnavailableConstructsAndExposesFields() {
    var r =
        new ResultMessage.ErrorProviderUnavailable(
            SID, "JShellExecutionProvider", "pool saturated", null, USAGE, COST, DUR);
    assertEquals("JShellExecutionProvider", r.providerName());
    assertEquals("pool saturated", r.reason());
    assertEquals(SID, r.sessionId());
  }

  @Test
  void errorProviderUnavailableRejectsNullProviderName() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new ResultMessage.ErrorProviderUnavailable(SID, null, "x", null, USAGE, COST, DUR));
    assertEquals("providerName must not be null", ex.getMessage());
  }

  @Test
  void errorProviderUnavailableRejectsBlankProviderName() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new ResultMessage.ErrorProviderUnavailable(SID, "  ", "x", null, USAGE, COST, DUR));
    assertEquals("providerName must not be blank", ex.getMessage());
  }

  @Test
  void errorProviderUnavailableRejectsNullReason() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new ResultMessage.ErrorProviderUnavailable(SID, "P", null, null, USAGE, COST, DUR));
    assertEquals("reason must not be null", ex.getMessage());
  }

  @Test
  void errorProviderUnavailableRejectsBlankReason() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new ResultMessage.ErrorProviderUnavailable(SID, "P", " ", null, USAGE, COST, DUR));
    assertEquals("reason must not be blank", ex.getMessage());
  }

  // ── Subtype: Cancelled ─────────────────────────────────────────────────────

  @Test
  void cancelledConstructsAndExposesFields() {
    var r = new ResultMessage.Cancelled(SID, "user-stop", USAGE, COST, DUR);
    assertEquals("user-stop", r.reason());
  }

  @Test
  void cancelledRejectsNullReason() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new ResultMessage.Cancelled(SID, null, USAGE, COST, DUR));
    assertEquals("reason must not be null", ex.getMessage());
  }

  @Test
  void cancelledRejectsBlankReason() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new ResultMessage.Cancelled(SID, " ", USAGE, COST, DUR));
    assertEquals("reason must not be blank", ex.getMessage());
  }

  // ── Sealed-hierarchy contract ─────────────────────────────────────────────

  @Test
  void sealedInterfaceHasExactlyEightPermittedSubclasses() {
    var permits = ResultMessage.class.getPermittedSubclasses();
    assertEquals(9, permits.length);
  }

  @Test
  void switchPatternHandlesEverySubtype() {
    List<ResultMessage> all =
        List.of(
            new ResultMessage.Success(SID, "ok", USAGE, COST, DUR),
            new ResultMessage.ErrorMaxTurns(SID, 5, USAGE, COST, DUR),
            new ResultMessage.ErrorMaxBudgetUsd(SID, 1_000_000L, USAGE, COST, DUR),
            new ResultMessage.ErrorMaxWallClock(SID, USAGE, COST, DUR),
            new ResultMessage.ErrorDuringExecution(
                SID, SerializedError.of("kind", "msg"), USAGE, COST, DUR),
            new ResultMessage.ErrorTransientStream(
                SID, "anthropic", 3, SerializedError.of("kind", "msg"), USAGE, COST, DUR),
            new ResultMessage.ErrorProviderUnavailable(
                SID, "TestProvider", "pool saturated", null, USAGE, COST, DUR),
            new ResultMessage.Refusal(SID, "no", USAGE, COST, DUR),
            new ResultMessage.Cancelled(SID, "stop", USAGE, COST, DUR));
    for (var r : all) {
      var tag =
          switch (r) {
            case ResultMessage.Success s -> "success";
            case ResultMessage.ErrorMaxTurns e -> "max-turns";
            case ResultMessage.ErrorMaxBudgetUsd e -> "max-budget";
            case ResultMessage.ErrorMaxWallClock e -> "max-wall";
            case ResultMessage.ErrorDuringExecution e -> "exec-error";
            case ResultMessage.ErrorTransientStream e -> "transient-stream";
            case ResultMessage.ErrorProviderUnavailable e -> "provider-unavailable";
            case ResultMessage.Refusal e -> "refusal";
            case ResultMessage.Cancelled e -> "cancelled";
          };
      assertTrue(tag != null && !tag.isEmpty());
    }
  }

  // ── withoutStackTraces ──────────────────────────────────────────────────────

  @Test
  void withoutStackTracesOnErrorDuringExecutionStripsFrames() {
    var carrying = SerializedError.of(new IllegalStateException("internal detail"));
    assertTrue(carrying.stackTrace().size() > 0, "test precondition");
    var terminal = new ResultMessage.ErrorDuringExecution(SID, carrying, USAGE, COST, DUR);
    var redacted = terminal.withoutStackTraces();
    assertTrue(redacted instanceof ResultMessage.ErrorDuringExecution);
    var redactedErr = ((ResultMessage.ErrorDuringExecution) redacted).error();
    assertEquals(carrying.kind(), redactedErr.kind(), "kind preserved");
    assertEquals(carrying.message(), redactedErr.message(), "message preserved");
    assertEquals(
        List.of(),
        redactedErr.stackTrace(),
        "frames stripped — they would otherwise leak internal class names and line numbers"
            + " through every HTTP terminal response and SSE LoopEnded event");
  }

  @Test
  void withoutStackTracesIsIdentityWhenNoError() {
    var success = new ResultMessage.Success(SID, "ok", USAGE, COST, DUR);
    assertSame(
        success,
        success.withoutStackTraces(),
        "no error → no allocation; identity preservation lets callers short-circuit cheaply");
  }

  @Test
  void withoutStackTracesIdentityWhenErrorAlreadyClean() {
    var clean = SerializedError.of("kind", "msg"); // no frames
    var terminal = new ResultMessage.ErrorDuringExecution(SID, clean, USAGE, COST, DUR);
    assertSame(terminal, terminal.withoutStackTraces());
  }
}
