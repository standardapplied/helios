/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.model.TransientStreamException;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SessionLimits;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class StopClassifierTest {

  private static final String SID = "sess-1";

  private final StopClassifier classifier = new StopClassifier();

  /**
   * Convenience overload mirroring the pre-2.5.5 5-arg shape — no stream error, single attempt. The
   * test body stays focused on the assertion it actually cares about instead of repeating {@code
   * null, 1} on every call.
   */
  private Optional<ResultMessage> classifyNoError(
      SessionState state,
      SessionLimits limits,
      FinishReason finishReason,
      String content,
      boolean hasPendingMessages) {
    return classifier.classify(
        state, limits, new TurnOutcome(finishReason, content, Usage.of(0, 0)), hasPendingMessages);
  }

  private ResultMessage classifyError(Throwable streamError, int streamAttempts, String content) {
    var outcome =
        new TurnOutcome(
            FinishReason.ERROR, content, Usage.of(0, 0), Map.of(), streamError, streamAttempts);
    return classifier.classify(state(), defaults(), outcome, false).orElseThrow();
  }

  private ResultMessage.Refusal classifyRefusal(String content, Map<String, String> metadata) {
    var outcome = new TurnOutcome(FinishReason.REFUSAL, content, Usage.of(0, 0), metadata);
    return assertInstanceOf(
        ResultMessage.Refusal.class,
        classifier.classify(state(), defaults(), outcome, false).orElseThrow());
  }

  private static SessionState state() {
    return state(new CancellationToken());
  }

  private static SessionState state(CancellationToken cancellation) {
    return new SessionState(SID, cancellation, Clock.systemUTC());
  }

  private static SessionState stateAtElapsed(Duration elapsed) {
    return stateAtElapsed(elapsed, new CancellationToken());
  }

  private static SessionState stateAtElapsed(Duration elapsed, CancellationToken cancellation) {
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
            return calls++ == 0 ? t0 : t0.plus(elapsed);
          }
        };
    return new SessionState(SID, cancellation, movingClock);
  }

  private static SessionLimits defaults() {
    return SessionLimits.defaults();
  }

  // ── argument validation ───────────────────────────────────────────────────

  @Test
  void rejectsNullState() {
    assertThrows(
        NullPointerException.class,
        () -> classifyNoError(null, defaults(), FinishReason.STOP, "x", false));
  }

  @Test
  void rejectsNullLimits() {
    assertThrows(
        NullPointerException.class,
        () -> classifyNoError(state(), null, FinishReason.STOP, "x", false));
  }

  // ── cancellation ──────────────────────────────────────────────────────────

  @Test
  void cancellationProducesCancelled() {
    var token = new CancellationToken();
    token.cancel("user-stop");
    var result = classifyNoError(state(token), defaults(), FinishReason.STOP, "ignored", false);
    var c = assertInstanceOf(ResultMessage.Cancelled.class, result.orElseThrow());
    assertEquals("user-stop", c.reason());
    assertEquals(SID, c.sessionId());
  }

  @Test
  void wallClockExceededTakesPriorityOverCancellation() {
    // The wall-clock deadline scheduler in AgentSessionImpl implements itself by cancelling the
    // session token; without the priority ordering the resulting terminal would be Cancelled
    // instead of the more informative ErrorMaxWallClock. Pins the ordering.
    var token = new CancellationToken();
    token.cancel("maxWallClock exceeded after 500ms");
    var s = stateAtElapsed(Duration.ofHours(2), token);
    var result = classifyNoError(s, defaults(), FinishReason.STOP, "ignored", false);
    assertInstanceOf(ResultMessage.ErrorMaxWallClock.class, result.orElseThrow());
  }

  // ── budget ────────────────────────────────────────────────────────────────

  @Test
  void budgetExceededProducesErrorMaxBudgetUsd() {
    var s = state();
    s.totals().accumulateCost(CostEstimate.ofMicroUsd(1_500_000L));
    var limits = SessionLimits.newBuilder().withMaxBudgetMicroUsd(1_000_000L).build();
    var result = classifyNoError(s, limits, FinishReason.STOP, "x", false);
    var b = assertInstanceOf(ResultMessage.ErrorMaxBudgetUsd.class, result.orElseThrow());
    assertEquals(1_500_000L, b.microUsdSpent());
  }

  @Test
  void budgetEqualToLimitDoesNotTrigger() {
    var s = state();
    s.totals().accumulateCost(CostEstimate.ofMicroUsd(1_000_000L));
    var limits = SessionLimits.newBuilder().withMaxBudgetMicroUsd(1_000_000L).build();
    var result = classifyNoError(s, limits, FinishReason.STOP, "done", false);
    assertInstanceOf(ResultMessage.Success.class, result.orElseThrow());
  }

  @Test
  void budgetAbsentDoesNotTrigger() {
    var s = state();
    s.totals().accumulateCost(CostEstimate.ofMicroUsd(999_999_000_000L));
    var result = classifyNoError(s, defaults(), FinishReason.STOP, "x", false);
    assertInstanceOf(ResultMessage.Success.class, result.orElseThrow());
  }

  // ── wall clock ────────────────────────────────────────────────────────────

  @Test
  void wallClockExceededProducesErrorMaxWallClock() {
    var s = stateAtElapsed(Duration.ofHours(2));
    var result = classifyNoError(s, defaults(), FinishReason.STOP, "x", false);
    assertInstanceOf(ResultMessage.ErrorMaxWallClock.class, result.orElseThrow());
  }

  @Test
  void wallClockExactDoesNotTrigger() {
    var s = stateAtElapsed(Duration.ofHours(1));
    var result = classifyNoError(s, defaults(), FinishReason.STOP, "x", false);
    assertInstanceOf(ResultMessage.Success.class, result.orElseThrow());
  }

  // ── turn ceiling ──────────────────────────────────────────────────────────

  @Test
  void turnCeilingReachedProducesErrorMaxTurns() {
    var s = state();
    var limits = SessionLimits.newBuilder().withMaxTurns(2).build();
    s.beginTurn();
    s.beginTurn(); // index now 2 == maxTurns
    var result = classifyNoError(s, limits, FinishReason.TOOL_CALLS, "x", false);
    var t = assertInstanceOf(ResultMessage.ErrorMaxTurns.class, result.orElseThrow());
    assertEquals(2, t.turnsUsed());
  }

  // ── finish-reason branches ────────────────────────────────────────────────

  @Test
  void contentFilterProducesRefusal() {
    var result =
        classifyNoError(state(), defaults(), FinishReason.CONTENT_FILTER, "I cannot", false);
    var r = assertInstanceOf(ResultMessage.Refusal.class, result.orElseThrow());
    assertEquals("I cannot", r.refusalText());
  }

  @Test
  void contentFilterWithBlankContentUsesPlaceholderRefusalText() {
    var result = classifyNoError(state(), defaults(), FinishReason.CONTENT_FILTER, "", false);
    var r = assertInstanceOf(ResultMessage.Refusal.class, result.orElseThrow());
    assertEquals("[refused without text]", r.refusalText());
  }

  @Test
  void refusalProducesRefusal() {
    var result =
        classifyNoError(state(), defaults(), FinishReason.REFUSAL, "declined by policy", false);
    var r = assertInstanceOf(ResultMessage.Refusal.class, result.orElseThrow());
    assertEquals("declined by policy", r.refusalText());
  }

  @Test
  void refusalWithBlankContentUsesPlaceholderRefusalText() {
    var result = classifyNoError(state(), defaults(), FinishReason.REFUSAL, "", false);
    var r = assertInstanceOf(ResultMessage.Refusal.class, result.orElseThrow());
    assertEquals("[refused without text]", r.refusalText());
  }

  @Test
  void refusalCarriesTheProviderReportedCategory() {
    var r =
        classifyRefusal(
            "", Map.of(Response.REFUSAL_CATEGORY_KEY, "cyber", "anthropic.stopReason", "refusal"));

    assertEquals("cyber", r.category());
    assertEquals(Optional.of("cyber"), r.categoryOpt());
  }

  @Test
  void refusalWithoutProviderCategoryHasNone() {
    var r = classifyRefusal("declined", Map.of());

    assertNull(r.category());
    assertEquals(Optional.empty(), r.categoryOpt());
  }

  @Test
  void refusalBeforeAnyOutputSurfacesTheProviderExplanation() {
    var r =
        classifyRefusal(
            "",
            Map.of(
                Response.REFUSAL_CATEGORY_KEY,
                "bio",
                Response.REFUSAL_EXPLANATION_KEY,
                "This request was declined because it could enable biological harm."));

    assertEquals(
        "This request was declined because it could enable biological harm.", r.refusalText());
  }

  @Test
  void refusalPrefersTheProviderExplanationOverOutputTheRefusalCutShort() {
    var r =
        classifyRefusal(
            "Here is the first half of an answer that",
            Map.of(Response.REFUSAL_EXPLANATION_KEY, "This request was declined."));

    assertEquals("This request was declined.", r.refusalText());
  }

  @Test
  void errorProducesErrorDuringExecution() {
    var result = classifyNoError(state(), defaults(), FinishReason.ERROR, "rate limited", false);
    var e = assertInstanceOf(ResultMessage.ErrorDuringExecution.class, result.orElseThrow());
    assertEquals("ProviderError", e.error().kind());
    assertEquals("rate limited", e.error().message());
  }

  @Test
  void errorWithBlankContentUsesPlaceholderMessage() {
    var result = classifyNoError(state(), defaults(), FinishReason.ERROR, "  ", false);
    var e = assertInstanceOf(ResultMessage.ErrorDuringExecution.class, result.orElseThrow());
    assertEquals("provider reported ERROR", e.error().message());
  }

  @Test
  void stopWithNoPendingMessagesProducesSuccess() {
    var result = classifyNoError(state(), defaults(), FinishReason.STOP, "all done", false);
    var s = assertInstanceOf(ResultMessage.Success.class, result.orElseThrow());
    assertEquals("all done", s.result());
  }

  @Test
  void stopWithPendingMessagesContinues() {
    var result = classifyNoError(state(), defaults(), FinishReason.STOP, "intermediate", true);
    assertTrue(result.isEmpty());
  }

  @Test
  void toolCallsContinues() {
    assertTrue(classifyNoError(state(), defaults(), FinishReason.TOOL_CALLS, "", false).isEmpty());
  }

  @Test
  void lengthTerminatesWithErrorDuringExecution() {
    // LENGTH means the provider truncated the response at its max_output_tokens cap. Returning
    // Optional.empty() makes the loop re-issue the same turn — the model will hit the same cap
    // and the loop iterates until maxTurns burns out (~100 turns of wasted budget). Classifying as
    // terminal at the first LENGTH avoids the burn and surfaces a meaningful error to the caller.
    var terminal =
        classifyNoError(state(), defaults(), FinishReason.LENGTH, "partial output", false)
            .orElseThrow();
    var err = (ResultMessage.ErrorDuringExecution) terminal;
    assertEquals("max-tokens", err.error().kind());
    assertTrue(
        err.error().message().contains("max_output_tokens")
            || err.error().message().contains("response truncated"),
        "message should indicate the response was truncated; got: " + err.error().message());
  }

  @Test
  void lengthTerminatesEvenWhenAssistantContentIsBlank() {
    // The model can hit LENGTH before producing any text (e.g. truncation mid-tool-call). The
    // terminal still carries an informative error kind so callers don't have to disambiguate.
    var terminal =
        classifyNoError(state(), defaults(), FinishReason.LENGTH, "", false).orElseThrow();
    assertTrue(terminal instanceof ResultMessage.ErrorDuringExecution);
  }

  @Test
  void usageAndCostFlowIntoResult() {
    var s = state();
    s.totals().accumulateUsage(Usage.of(20, 10));
    s.totals().accumulateCost(CostEstimate.ofMicroUsd(420_000L));
    var result = classifyNoError(s, defaults(), FinishReason.STOP, "ok", false).orElseThrow();
    assertEquals(20, result.usage().inputTokens());
    assertEquals(10, result.usage().outputTokens());
    assertEquals(420_000L, result.cost().microUsd());
  }

  @Test
  void outcomeRejectsNonPositiveStreamAttempts() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new TurnOutcome(FinishReason.STOP, "x", Usage.of(0, 0), Map.of(), null, 0));
    assertTrue(ex.getMessage().contains("streamAttempts"));
  }

  @Test
  void nullOutcomeIsRejected() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> classifier.classify(state(), defaults(), null, false));
    assertEquals("outcome must not be null", ex.getMessage());
  }

  // ── ERROR branch: TransientStreamException → ErrorTransientStream ─────────

  @Test
  void transientStreamExceptionProducesErrorTransientStreamWithProviderAndAttempts() {
    var ioe = new IOException("Connection reset by peer");
    var tse = new TransientStreamException("Stream read error", ioe, "anthropic");
    var terminal = classifyError(tse, 3, "");
    var ets = assertInstanceOf(ResultMessage.ErrorTransientStream.class, terminal);
    assertEquals("anthropic", ets.providerName());
    assertEquals(3, ets.attemptsMade());
    assertEquals(TransientStreamException.class.getName(), ets.error().kind());
    assertEquals(IOException.class.getName(), ets.error().cause().kind());
    assertTrue(ets.error().cause().message().contains("Connection reset"));
  }

  // ── ERROR branch: arbitrary throwable preserves the cause chain ───────────

  @Test
  void nonTransientThrowableProducesErrorDuringExecutionWithFullCauseChain() {
    var inner = new IllegalStateException("inner");
    var outer = new RuntimeException("outer", inner);
    var terminal = classifyError(outer, 1, "");
    var err = assertInstanceOf(ResultMessage.ErrorDuringExecution.class, terminal);
    assertEquals(RuntimeException.class.getName(), err.error().kind());
    assertEquals("outer", err.error().message());
    assertEquals(IllegalStateException.class.getName(), err.error().cause().kind());
    assertEquals("inner", err.error().cause().message());
  }

  // ── ERROR branch: null throwable retains the legacy assistant-content path ─

  @Test
  void nullThrowableInErrorBranchFallsBackToAssistantContentMessage() {
    var terminal = classifyError(null, 1, "rate limited");
    var err = assertInstanceOf(ResultMessage.ErrorDuringExecution.class, terminal);
    assertEquals("ProviderError", err.error().kind());
    assertEquals("rate limited", err.error().message());
  }
}
