/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.testing.ScriptedModel;
import com.sun.management.HotSpotDiagnosticMXBean;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression coverage for the "session hangs past {@code maxWallClock}" bug surfaced in a client
 * stack trace where the JVM main thread parked at {@code AgentSession.runBlocking} → {@code
 * CompletableFuture.join()} for 494 s despite a 2-minute wall-clock cap. Root cause: {@link
 * com.standardapplied.helios.session.loop.StopClassifier} checks {@code maxWallClock} only between
 * turns, while the loop waited indefinitely for a provider stream that may never end (silent
 * socket, hung proxy, mid-stream stall).
 *
 * <p>The model's stream blocks its first read until it is closed — the bare-minimum hang. The only
 * limit short enough to end that wait is the 500 ms {@code maxWallClock}: the terminal is {@link
 * ResultMessage.ErrorMaxWallClock}, and the session closed the stream. The deadline is a virtual
 * thread waiting on the session's token: no platform thread runs it, and nothing a caller does to
 * the result future disarms it.
 */
final class AgentSessionWallClockTest {

  private static final String SESSION_ID = "sess-hang-wallclock";
  private static final String EXPIRED = "maxWallClock exceeded after 500ms";
  private static final String WALL_CLOCK_THREAD = "\"helios-wall-clock-" + SESSION_ID + "\"";

  private final CountDownLatch entered = new CountDownLatch(1);
  private final CountDownLatch closed = new CountDownLatch(1);
  private final RecordingProvider provider = new RecordingProvider(new CopyOnWriteArrayList<>());

  @Test
  void maxWallClockTerminatesHangingChatStream() {
    try (var session = AgentSession.create(options())) {
      session.send(UserMessage.text("hi"));
      var terminal = Await.value("the wall-clock terminal", session.result());
      assertInstanceOf(
          ResultMessage.ErrorMaxWallClock.class,
          terminal,
          () ->
              "session must surface ErrorMaxWallClock when chatStream hangs past maxWallClock — "
                  + "got "
                  + terminal.getClass().getSimpleName()
                  + ": "
                  + terminal);
      assertEquals(Optional.of(EXPIRED), provider.context.cancellation().reason());
    }
    Await.latch("the stream closed", closed);
  }

  /**
   * A caller that completes the public result future, as {@code orTimeout} does when it fires,
   * leaves the deadline armed: the wall clock waits on the session's token, never on that future.
   */
  @Test
  void aCallerThatCompletesTheResultFutureDoesNotDisarmTheDeadline() {
    try (var session = AgentSession.create(options())) {
      session.send(UserMessage.text("hi"));
      Await.latch("the stream to be read", entered);

      session.result().completeExceptionally(new TimeoutException("the caller's own timeout"));

      assertInstanceOf(
          TimeoutException.class, Await.failure("the caller's timeout", session.result()));
      Await.latch("the deadline to close the stream", closed);
      assertEquals(Optional.of(EXPIRED), provider.context.cancellation().reason());
    }
  }

  @Test
  void aRunningSessionHoldsNoPlatformThreadAndItsWallClockIsAVirtualOne(@TempDir Path dumps) {
    try (var session = AgentSession.create(options())) {
      session.send(UserMessage.text("hi"));
      Await.latch("the stream to be read", entered);

      var names = Thread.getAllStackTraces().keySet().stream().map(Thread::getName).toList();

      assertFalse(names.stream().anyMatch(name -> name.contains(SESSION_ID)), names::toString);
      assertTrue(threadDump(dumps).contains(WALL_CLOCK_THREAD), "the wall clock runs");
    }
  }

  @Test
  void aSessionThatEndsBeforeItsDeadlineLeavesNoWallClockThreadBehind(@TempDir Path dumps) {
    var options =
        options().toBuilder()
            .withModel(ScriptedModel.newBuilder().withTextTurn("done").build())
            .withLimits(SessionLimits.defaults())
            .build();
    try (var session = AgentSession.create(options)) {
      session.send(UserMessage.text("hi"));
      assertInstanceOf(ResultMessage.Success.class, Await.value("the result", session.result()));
    }

    Await.until("the wall clock to end", () -> !threadDump(dumps).contains(WALL_CLOCK_THREAD));
  }

  /** Every live thread, virtual ones included, as the JVM's JSON thread dump lists them. */
  private static String threadDump(Path dumps) {
    var dump = dumps.resolve(UUID.randomUUID() + ".json");
    try {
      ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
          .dumpThreads(dump.toString(), HotSpotDiagnosticMXBean.ThreadDumpFormat.JSON);
      return Files.readString(dump);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private SessionOptions options() {
    var model = ScriptedModel.newBuilder().withStreamTurn(blockingUntilClosed()).build();
    return SessionOptions.newBuilder()
        .withModel(model)
        .withSessionId(SESSION_ID)
        .withExecutionProvider(provider)
        .withLimits(SessionLimits.newBuilder().withMaxWallClock(Duration.ofMillis(500)).build())
        .build();
  }

  private CloseableIterator<ModelChunk> blockingUntilClosed() {
    return new CloseableIterator<>() {
      @Override
      public boolean hasNext() {
        entered.countDown();
        Await.latch("the stream closed", closed);
        throw new CancellationException("closed");
      }

      @Override
      public ModelChunk next() {
        throw new NoSuchElementException();
      }

      @Override
      public void close() {
        closed.countDown();
      }
    };
  }
}
