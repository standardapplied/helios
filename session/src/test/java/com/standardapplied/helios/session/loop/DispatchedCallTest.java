/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolResult;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The interleaving {@link ToolDispatchTest} cannot force through the dispatcher: the call is
 * cancelled after its worker was granted the permit and before the tool starts. A permit pool that
 * cancels the call as it grants the permit puts the cancellation exactly there.
 */
final class DispatchedCallTest {

  private static final Duration NEVER = Duration.ofMinutes(10);

  private final ManualScheduler scheduler = new ManualScheduler();

  @AfterEach
  void stopTheScheduler() {
    scheduler.shutdownNow();
  }

  /** Grants the permit, then cancels {@link #call} before handing control back to the worker. */
  private static final class CancellingOnGrant extends Semaphore {

    private final AtomicReference<DispatchedCall> call = new AtomicReference<>();

    CancellingOnGrant() {
      super(1);
    }

    @Override
    public void acquire() throws InterruptedException {
      super.acquire();
      call.get().cancel("cancelled as the permit was granted");
    }
  }

  @Test
  void aCallCancelledAfterItsPermitIsGrantedNeverStartsItsTool() {
    var runs = new AtomicInteger();
    var tool =
        Tool.newBuilder()
            .withName("t")
            .withDescription("t")
            .withExecutor((args, ctx) -> ToolResult.success("run " + runs.incrementAndGet()))
            .build();
    var permits = new CancellingOnGrant();
    var live = new AtomicInteger();
    var dispatched =
        new DispatchedCall(
            tool,
            new ToolCall("c", "t", Map.of()),
            SessionContext.forTesting("dispatched-call"),
            NEVER,
            permits,
            live);
    permits.call.set(dispatched);

    dispatched.start(new CancellationToken(), scheduler);

    var awaited = new FutureTask<>(() -> dispatched.await(NEVER));
    Thread.ofVirtual().start(awaited);

    var failure = Await.failure("the cancelled call's answer", awaited);
    assertInstanceOf(CancellationException.class, failure);
    assertEquals("cancelled as the permit was granted", failure.getMessage());
    Await.until("the worker to end", () -> live.get() == 0);
    assertEquals(0, runs.get(), "the tool never ran");
    assertEquals(1, permits.availablePermits(), "the permit is back");
  }
}
