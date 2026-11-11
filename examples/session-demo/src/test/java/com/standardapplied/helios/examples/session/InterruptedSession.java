/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.examples.session;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SessionLimits;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.PreModelTurnHook;
import com.standardapplied.helios.session.store.MessageOrigin;
import com.standardapplied.helios.session.test.EventCollector;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

/**
 * The live session the partial-turn tests run: a first run asked for a long answer is interrupted
 * at its first {@code AssistantText}, then a second run of the same session asks the model to go
 * on. The provider has never returned the partial assistant turn the second run sends, and its
 * documentation does not say it accepts one; the second run's acceptance is the proof.
 */
final class InterruptedSession {

  private InterruptedSession() {}

  /**
   * Runs the two runs over {@code model} and asserts what holds whatever the model writes: the
   * first run ended interrupted or, had the model finished first, in success; the second run ended
   * in success; no {@code Error} event. Whether the interrupt cut the answer is a race the model
   * can win, so a second run that sent no partial skips.
   */
  static void interruptThenGoOn(Model model) {
    var firstText = new CountDownLatch(1);
    var requests = new CopyOnWriteArrayList<List<Message>>();
    PreModelTurnHook recording =
        (history, ctx) -> {
          requests.add(history);
          return HookOutcome.cont();
        };
    var options =
        SessionOptions.newBuilder()
            .withModel(model)
            .withHook(recording)
            .withLimits(SessionLimits.newBuilder().withMaxTurns(2).build())
            .build();
    try (var session = AgentSession.open(options)) {
      var events =
          new EventCollector(
              event -> {
                if (event instanceof QueryEvent.AssistantText) {
                  firstText.countDown();
                }
              });
      events.read(session.events());

      var first = session.send("Write a 600-word story about a lighthouse keeper and a stray cat.");
      Await.latch("the first run's first AssistantText", firstText);
      session.interrupt();
      var interrupted = first.join();
      requests.clear();
      var continued = session.send("Go on from where you stopped.").join();
      session.close();
      events.awaitDone();

      assertTrue(
          interrupted instanceof ResultMessage.Cancelled
              || interrupted instanceof ResultMessage.Success,
          () -> model.id() + " ended the interrupted run as " + interrupted);
      assertTrue(
          continued instanceof ResultMessage.Success,
          () -> model.id() + " ended the run after the interrupt as " + continued);
      assertTrue(events.eventsOf(QueryEvent.Error.class).isEmpty(), model.id());
      assumeTrue(
          requests.getFirst().stream()
              .anyMatch(m -> MessageOrigin.of(m).equals(Optional.of(MessageOrigin.PARTIAL_TURN))),
          model.id()
              + " finished its answer before the interrupt, so no partial turn was sent;"
              + " PartialTurnSessionTest#theNextRunOfTheSameSessionSendsThePartial and"
              + " ConversationRequestContract#aPartialTurnIsSentAsATextOnlyAssistantTurnWithout"
              + "TheSessionsTag cover the request");
    }
  }
}
