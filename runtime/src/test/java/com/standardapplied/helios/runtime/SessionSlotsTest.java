/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static com.standardapplied.helios.runtime.ServerStubs.disconnectedResponse;
import static com.standardapplied.helios.runtime.ServerStubs.untouchedResponse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.helidon.webserver.CloseConnectionException;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A request refused by the principal's or the service's bound gives its session's slot back even
 * when the refusal cannot be sent, so a client that disconnects never uses up a session's slots.
 */
final class SessionSlotsTest {

  private static final Principal ALICE = new Principal("acme", "alice");
  private static final Principal BOB = new Principal("acme", "bob");

  static Stream<Function<RequestLimits, SessionSlots>> kinds() {
    return Stream.of(SessionSlots::eventStreams, SessionSlots::resultPolls);
  }

  @ParameterizedTest
  @MethodSource("kinds")
  void aPrincipalRefusalThatFailsToSendGivesTheSessionSlotBack(
      Function<RequestLimits, SessionSlots> kind) {
    var slots = kind.apply(limits(1, 2));

    assertTrue(slots.admit("a", ALICE, untouchedResponse()));
    assertThrows(
        CloseConnectionException.class, () -> slots.admit("b", ALICE, disconnectedResponse()));
    slots.release("a", ALICE);

    assertTrue(slots.admit("b", ALICE, untouchedResponse()));
    assertEquals(new AdmissionCounts(2, 1), slots.counts());
  }

  @ParameterizedTest
  @MethodSource("kinds")
  void aServiceRefusalThatFailsToSendGivesTheSessionSlotBack(
      Function<RequestLimits, SessionSlots> kind) {
    var slots = kind.apply(limits(1, 1));

    assertTrue(slots.admit("a", ALICE, untouchedResponse()));
    assertThrows(
        CloseConnectionException.class, () -> slots.admit("b", BOB, disconnectedResponse()));
    slots.release("a", ALICE);

    assertTrue(slots.admit("b", BOB, untouchedResponse()));
    assertEquals(new AdmissionCounts(2, 1), slots.counts());
  }

  private static RequestLimits limits(int perPrincipal, int total) {
    return RequestLimits.newBuilder()
        .withMaxEventStreamsPerSession(1)
        .withMaxEventStreamsPerPrincipal(perPrincipal)
        .withMaxEventStreams(total)
        .withMaxResultPollsPerSession(1)
        .withMaxResultPollsPerPrincipal(perPrincipal)
        .withMaxResultPolls(total)
        .build();
  }
}
