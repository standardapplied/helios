/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.CostEstimate;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class SessionLimitsTest {

  @Test
  void canonicalConstructorAcceptsValidValues() {
    var l =
        new SessionLimits(
            50,
            OptionalLong.of(12_500_000L),
            Duration.ofMinutes(30),
            Duration.ofSeconds(45),
            Duration.ofSeconds(3),
            32_000L,
            Duration.ofSeconds(30),
            StreamRetryPolicy.defaults(),
            Duration.ofSeconds(7),
            Duration.ofSeconds(2));
    assertEquals(50, l.maxTurns());
    assertEquals(OptionalLong.of(12_500_000L), l.maxBudgetMicroUsd());
    assertEquals(Duration.ofMinutes(30), l.maxWallClock());
    assertEquals(Duration.ofSeconds(45), l.toolTimeoutDefault());
    assertEquals(Duration.ofSeconds(3), l.toolStopGrace());
    assertEquals(32_000L, l.maxContextTokens());
    assertEquals(Duration.ofSeconds(30), l.streamIdleTimeout());
    assertEquals(StreamRetryPolicy.defaults(), l.streamRetryPolicy());
    assertEquals(Duration.ofSeconds(7), l.startTimeout());
    assertEquals(Duration.ofSeconds(2), l.teardownGrace());
  }

  @Test
  void defaultsExposesExpectedValues() {
    var d = SessionLimits.defaults();
    assertEquals(100, d.maxTurns());
    assertTrue(d.maxBudgetMicroUsd().isEmpty());
    assertEquals(Duration.ofHours(1), d.maxWallClock());
    assertEquals(Duration.ofMinutes(2), d.toolTimeoutDefault());
    assertEquals(Duration.ofSeconds(5), d.toolStopGrace());
    assertEquals(0L, d.maxContextTokens());
    assertEquals(Duration.ofSeconds(60), d.streamIdleTimeout());
    assertEquals(StreamRetryPolicy.defaults(), d.streamRetryPolicy());
    assertEquals(Duration.ofSeconds(60), d.startTimeout());
    assertEquals(Duration.ofSeconds(5), d.teardownGrace());
  }

  @Test
  void defaultsReturnsSameSingleton() {
    assertSame(SessionLimits.defaults(), SessionLimits.defaults());
  }

  // ── maxTurns ──────────────────────────────────────────────────────────────

  @Test
  void maxTurnsZeroRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> newLimits(0, OptionalLong.empty(), oneHour(), twoMin(), 1_000L));
    assertEquals("maxTurns must be positive, got 0", ex.getMessage());
  }

  @Test
  void maxTurnsNegativeRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> newLimits(-1, OptionalLong.empty(), oneHour(), twoMin(), 1_000L));
    assertEquals("maxTurns must be positive, got -1", ex.getMessage());
  }

  // ── maxBudgetMicroUsd ─────────────────────────────────────────────────────

  @Test
  void maxBudgetMicroUsdNullOptionalRejected() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> newLimits(1, null, oneHour(), twoMin(), 1_000L));
    assertEquals("maxBudgetMicroUsd must not be null", ex.getMessage());
  }

  @Test
  void maxBudgetMicroUsdZeroRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> newLimits(1, OptionalLong.of(0L), oneHour(), twoMin(), 1_000L));
    assertTrue(ex.getMessage().startsWith("maxBudgetMicroUsd must be positive when present"));
  }

  @Test
  void maxBudgetMicroUsdNegativeRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> newLimits(1, OptionalLong.of(-1L), oneHour(), twoMin(), 1_000L));
    assertTrue(ex.getMessage().contains("-1"));
  }

  @Test
  void maxBudgetMicroUsdEmptyAccepted() {
    var l = newLimits(1, OptionalLong.empty(), oneHour(), twoMin(), 1_000L);
    assertFalse(l.maxBudgetMicroUsd().isPresent());
  }

  // ── maxWallClock ──────────────────────────────────────────────────────────

  @Test
  void maxWallClockNullRejected() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> newLimits(1, OptionalLong.empty(), null, twoMin(), 1_000L));
    assertEquals("maxWallClock must not be null", ex.getMessage());
  }

  @Test
  void maxWallClockZeroRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> newLimits(1, OptionalLong.empty(), Duration.ZERO, twoMin(), 1_000L));
    assertTrue(ex.getMessage().startsWith("maxWallClock must be strictly positive"));
  }

  @Test
  void maxWallClockNegativeRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> newLimits(1, OptionalLong.empty(), Duration.ofSeconds(-1), twoMin(), 1_000L));
    assertTrue(ex.getMessage().startsWith("maxWallClock must be strictly positive"));
  }

  // ── toolTimeoutDefault ────────────────────────────────────────────────────

  @Test
  void toolTimeoutDefaultNullRejected() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> newLimits(1, OptionalLong.empty(), oneHour(), null, 1_000L));
    assertEquals("toolTimeoutDefault must not be null", ex.getMessage());
  }

  @Test
  void toolTimeoutDefaultZeroRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> newLimits(1, OptionalLong.empty(), oneHour(), Duration.ZERO, 1_000L));
    assertTrue(ex.getMessage().startsWith("toolTimeoutDefault must be strictly positive"));
  }

  @Test
  void toolTimeoutDefaultNegativeRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> newLimits(1, OptionalLong.empty(), oneHour(), Duration.ofMillis(-1), 1_000L));
    assertTrue(ex.getMessage().startsWith("toolTimeoutDefault must be strictly positive"));
  }

  // ── toolStopGrace ─────────────────────────────────────────────────────────

  @Test
  void toolStopGraceNullRejected() {
    var b = SessionLimits.newBuilder().withToolStopGrace(null);
    var ex = assertThrows(NullPointerException.class, b::build);
    assertEquals("toolStopGrace must not be null", ex.getMessage());
  }

  @Test
  void toolStopGraceZeroRejected() {
    var b = SessionLimits.newBuilder().withToolStopGrace(Duration.ZERO);
    var ex = assertThrows(IllegalArgumentException.class, b::build);
    assertEquals("toolStopGrace must be strictly positive, got PT0S", ex.getMessage());
  }

  @Test
  void toolStopGraceNegativeRejected() {
    var b = SessionLimits.newBuilder().withToolStopGrace(Duration.ofMillis(-1));
    var ex = assertThrows(IllegalArgumentException.class, b::build);
    assertEquals("toolStopGrace must be strictly positive, got PT-0.001S", ex.getMessage());
  }

  // ── maxContextTokens ──────────────────────────────────────────────────────

  @Test
  void maxContextTokensZeroAccepted() {
    var l = newLimits(1, OptionalLong.empty(), oneHour(), twoMin(), 0L);
    assertEquals(0L, l.maxContextTokens());
  }

  @Test
  void maxContextTokensNegativeRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> newLimits(1, OptionalLong.empty(), oneHour(), twoMin(), -5L));
    assertEquals("maxContextTokens must be non-negative, got -5", ex.getMessage());
  }

  // ── Builder ───────────────────────────────────────────────────────────────

  @Test
  void newBuilderStartsAtDefaults() {
    assertEquals(SessionLimits.defaults(), SessionLimits.newBuilder().build());
  }

  @Test
  void builderOverridesEachFieldIndependently() {
    var l =
        SessionLimits.newBuilder()
            .withMaxTurns(10)
            .withMaxBudgetMicroUsd(5 * CostEstimate.MICRO_USD_PER_USD)
            .withMaxWallClock(Duration.ofMinutes(5))
            .withToolTimeoutDefault(Duration.ofSeconds(30))
            .withToolStopGrace(Duration.ofSeconds(9))
            .withMaxContextTokens(50_000L)
            .build();
    assertEquals(10, l.maxTurns());
    assertEquals(OptionalLong.of(5_000_000L), l.maxBudgetMicroUsd());
    assertEquals(Duration.ofMinutes(5), l.maxWallClock());
    assertEquals(Duration.ofSeconds(30), l.toolTimeoutDefault());
    assertEquals(Duration.ofSeconds(9), l.toolStopGrace());
    assertEquals(50_000L, l.maxContextTokens());
  }

  @Test
  void builderWithoutMaxBudgetClearsBudget() {
    var l = SessionLimits.newBuilder().withMaxBudgetMicroUsd(1_000_000L).withoutMaxBudget().build();
    assertTrue(l.maxBudgetMicroUsd().isEmpty());
  }

  @Test
  void builderRejectsNullOptionalBudget() {
    var b = SessionLimits.newBuilder();
    var ex = assertThrows(NullPointerException.class, () -> b.withMaxBudgetMicroUsd(null));
    assertEquals("maxBudgetMicroUsd must not be null", ex.getMessage());
  }

  @Test
  void toBuilderRoundTripsAllFields() {
    var original =
        SessionLimits.newBuilder()
            .withMaxTurns(7)
            .withMaxBudgetMicroUsd(2_500_000L)
            .withMaxWallClock(Duration.ofMinutes(15))
            .withToolTimeoutDefault(Duration.ofSeconds(20))
            .withToolStopGrace(Duration.ofSeconds(1))
            .withStartTimeout(Duration.ofSeconds(4))
            .withTeardownGrace(Duration.ofSeconds(9))
            .withMaxContextTokens(75_000L)
            .build();
    assertEquals(original, original.toBuilder().build());
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static SessionLimits newLimits(
      int maxTurns,
      OptionalLong maxBudgetMicroUsd,
      Duration maxWallClock,
      Duration toolTimeoutDefault,
      long maxContextTokens) {
    return new SessionLimits(
        maxTurns,
        maxBudgetMicroUsd,
        maxWallClock,
        toolTimeoutDefault,
        fiveSec(),
        maxContextTokens,
        Duration.ofSeconds(60),
        StreamRetryPolicy.defaults(),
        fiveSec(),
        fiveSec());
  }

  private static Duration oneHour() {
    return Duration.ofHours(1);
  }

  private static Duration twoMin() {
    return Duration.ofMinutes(2);
  }

  private static Duration fiveSec() {
    return Duration.ofSeconds(5);
  }

  // ── streamIdleTimeout ─────────────────────────────────────────────────────

  @Test
  void streamIdleTimeoutZeroRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new SessionLimits(
                    100,
                    OptionalLong.empty(),
                    oneHour(),
                    twoMin(),
                    fiveSec(),
                    1_000L,
                    Duration.ZERO,
                    StreamRetryPolicy.defaults(),
                    fiveSec(),
                    fiveSec()));
    assertEquals("streamIdleTimeout must be strictly positive, got PT0S", ex.getMessage());
  }

  @Test
  void streamIdleTimeoutNegativeRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SessionLimits(
                100,
                OptionalLong.empty(),
                oneHour(),
                twoMin(),
                fiveSec(),
                1_000L,
                Duration.ofSeconds(-1),
                StreamRetryPolicy.defaults(),
                fiveSec(),
                fiveSec()));
  }

  @Test
  void streamIdleTimeoutNullRejected() {
    assertThrows(
        NullPointerException.class,
        () ->
            new SessionLimits(
                100,
                OptionalLong.empty(),
                oneHour(),
                twoMin(),
                fiveSec(),
                1_000L,
                null,
                StreamRetryPolicy.defaults(),
                fiveSec(),
                fiveSec()));
  }

  @Test
  void builderWithStreamIdleTimeout() {
    var l = SessionLimits.newBuilder().withStreamIdleTimeout(Duration.ofSeconds(15)).build();
    assertEquals(Duration.ofSeconds(15), l.streamIdleTimeout());
  }

  // ── streamRetryPolicy ─────────────────────────────────────────────────────

  @Test
  void streamRetryPolicyNullRejected() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new SessionLimits(
                    100,
                    OptionalLong.empty(),
                    oneHour(),
                    twoMin(),
                    fiveSec(),
                    1_000L,
                    Duration.ofSeconds(60),
                    null,
                    fiveSec(),
                    fiveSec()));
    assertEquals("streamRetryPolicy must not be null", ex.getMessage());
  }

  @Test
  void builderWithStreamRetryPolicy() {
    var disabled = StreamRetryPolicy.disabled();
    var l = SessionLimits.newBuilder().withStreamRetryPolicy(disabled).build();
    assertEquals(disabled, l.streamRetryPolicy());
  }

  @Test
  void toBuilderPreservesStreamRetryPolicyOverride() {
    var disabled = StreamRetryPolicy.disabled();
    var original = SessionLimits.newBuilder().withStreamRetryPolicy(disabled).build();
    assertEquals(disabled, original.toBuilder().build().streamRetryPolicy());
  }

  // ── startTimeout ──────────────────────────────────────────────────────────

  @Test
  void startTimeoutZeroRejected() {
    var b = SessionLimits.newBuilder().withStartTimeout(Duration.ZERO);
    var ex = assertThrows(IllegalArgumentException.class, b::build);
    assertEquals("startTimeout must be strictly positive, got PT0S", ex.getMessage());
  }

  @Test
  void startTimeoutNullRejected() {
    var b = SessionLimits.newBuilder().withStartTimeout(null);
    var ex = assertThrows(NullPointerException.class, b::build);
    assertEquals("startTimeout must not be null", ex.getMessage());
  }

  // ── teardownGrace ─────────────────────────────────────────────────────────

  @Test
  void builderWithTeardownGrace() {
    var l = SessionLimits.newBuilder().withTeardownGrace(Duration.ofMillis(250)).build();
    assertEquals(Duration.ofMillis(250), l.teardownGrace());
  }

  @Test
  void teardownGraceZeroRejected() {
    var b = SessionLimits.newBuilder().withTeardownGrace(Duration.ZERO);
    var ex = assertThrows(IllegalArgumentException.class, b::build);
    assertEquals("teardownGrace must be strictly positive, got PT0S", ex.getMessage());
  }

  @Test
  void teardownGraceNegativeRejected() {
    var b = SessionLimits.newBuilder().withTeardownGrace(Duration.ofSeconds(-1));
    var ex = assertThrows(IllegalArgumentException.class, b::build);
    assertEquals("teardownGrace must be strictly positive, got PT-1S", ex.getMessage());
  }

  @Test
  void teardownGraceNullRejected() {
    var b = SessionLimits.newBuilder().withTeardownGrace(null);
    var ex = assertThrows(NullPointerException.class, b::build);
    assertEquals("teardownGrace must not be null", ex.getMessage());
  }

  // ── duration bound ────────────────────────────────────────────────────────

  private static final Duration LONGEST = Duration.ofNanos(Long.MAX_VALUE);

  static Stream<Arguments> durations() {
    return Stream.of(
        duration(
            "maxWallClock", SessionLimits.Builder::withMaxWallClock, SessionLimits::maxWallClock),
        duration(
            "toolTimeoutDefault",
            SessionLimits.Builder::withToolTimeoutDefault,
            SessionLimits::toolTimeoutDefault),
        duration(
            "toolStopGrace",
            SessionLimits.Builder::withToolStopGrace,
            SessionLimits::toolStopGrace),
        duration(
            "streamIdleTimeout",
            SessionLimits.Builder::withStreamIdleTimeout,
            SessionLimits::streamIdleTimeout),
        duration(
            "startTimeout", SessionLimits.Builder::withStartTimeout, SessionLimits::startTimeout),
        duration(
            "teardownGrace",
            SessionLimits.Builder::withTeardownGrace,
            SessionLimits::teardownGrace));
  }

  private static Arguments duration(
      String name,
      BiFunction<SessionLimits.Builder, Duration, SessionLimits.Builder> setter,
      Function<SessionLimits, Duration> getter) {
    return Arguments.of(name, setter, getter);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("durations")
  void aDurationOfLongMaxValueNanosecondsIsAccepted(
      String name,
      BiFunction<SessionLimits.Builder, Duration, SessionLimits.Builder> setter,
      Function<SessionLimits, Duration> getter) {
    var limits = setter.apply(SessionLimits.newBuilder(), LONGEST).build();

    assertEquals(LONGEST, getter.apply(limits));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("durations")
  void aDurationBeyondLongMaxValueNanosecondsIsRejected(
      String name,
      BiFunction<SessionLimits.Builder, Duration, SessionLimits.Builder> setter,
      Function<SessionLimits, Duration> getter) {
    var builder = setter.apply(SessionLimits.newBuilder(), LONGEST.plusNanos(1));

    var ex = assertThrows(IllegalArgumentException.class, builder::build);

    assertEquals(
        name
            + " must be at most PT2562047H47M16.854775807S (Long.MAX_VALUE ns), got"
            + " PT2562047H47M16.854775808S",
        ex.getMessage());
  }
}
