/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.CostEstimate;
import ai.singlr.core.model.Response.Usage;
import ai.singlr.session.QueryEvent;
import ai.singlr.session.ResultMessage;
import ai.singlr.session.SerializedError;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AgentHttpService#redactForWire(QueryEvent)}. The SSE emitter routes every
 * outgoing event through this helper so {@code LoopEnded} (which carries the terminal {@link
 * ResultMessage}) and standalone {@link QueryEvent.Error} events do not leak internal stack frames
 * to subscribers.
 */
final class AgentHttpServiceRedactForWireTest {

  private static final String SID = "sess-1";
  private static final Instant NOW = Instant.parse("2026-05-18T10:00:00Z");

  @Test
  void loopEndedWithErrorDuringExecutionIsRedacted() {
    var withFrames = SerializedError.of(new IllegalStateException("internal"));
    assertTrue(withFrames.stackTrace().size() > 0, "test precondition");
    var terminal =
        new ResultMessage.ErrorDuringExecution(
            SID, withFrames, Usage.of(1, 1), CostEstimate.zero(), Duration.ZERO);
    var event = new QueryEvent.LoopEnded(SID, 0L, NOW, terminal);

    var redacted = AgentHttpService.redactForWire(event);

    assertTrue(redacted instanceof QueryEvent.LoopEnded);
    var redactedTerminal =
        (ResultMessage.ErrorDuringExecution) ((QueryEvent.LoopEnded) redacted).result();
    assertEquals(List.of(), redactedTerminal.error().stackTrace());
    assertEquals(withFrames.kind(), redactedTerminal.error().kind());
    assertEquals(withFrames.message(), redactedTerminal.error().message());
  }

  @Test
  void loopEndedWithSuccessIsIdentity() {
    var terminal =
        new ResultMessage.Success(SID, "ok", Usage.of(1, 1), CostEstimate.zero(), Duration.ZERO);
    var event = new QueryEvent.LoopEnded(SID, 0L, NOW, terminal);
    assertSame(event, AgentHttpService.redactForWire(event));
  }

  @Test
  void standaloneErrorEventIsRedacted() {
    var withFrames = SerializedError.of(new RuntimeException("boom"));
    assertTrue(withFrames.stackTrace().size() > 0, "test precondition");
    var event = new QueryEvent.Error(SID, 0L, NOW, withFrames);

    var redacted = AgentHttpService.redactForWire(event);

    assertTrue(redacted instanceof QueryEvent.Error);
    assertEquals(List.of(), ((QueryEvent.Error) redacted).error().stackTrace());
  }

  @Test
  void standaloneErrorEventWithEmptyStackIsIdentity() {
    var cleanErr = SerializedError.of("kind", "msg"); // no frames
    var event = new QueryEvent.Error(SID, 0L, NOW, cleanErr);
    assertSame(event, AgentHttpService.redactForWire(event));
  }

  @Test
  void nonErrorEventIsPassedThroughUnchanged() {
    var event = new QueryEvent.AssistantText(SID, 0L, NOW, "hello");
    assertSame(event, AgentHttpService.redactForWire(event));
  }
}
