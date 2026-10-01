/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.core.tool.Tool;
import java.util.List;
import org.junit.jupiter.api.Test;

final class DefaultHookContextTest {

  private static Model stubModel() {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        return Response.newBuilder().build();
      }

      @Override
      public String id() {
        return "stub";
      }

      @Override
      public String provider() {
        return "stub";
      }
    };
  }

  @Test
  void happyPathExposesAllFields() {
    var tok = new CancellationToken();
    var model = stubModel();
    var ctx = new DefaultHookContext("sess-1", 3L, tok, model);
    assertEquals("sess-1", ctx.sessionId());
    assertEquals(3L, ctx.turnIndex());
    assertSame(tok, ctx.cancellation());
    assertSame(model, ctx.model());
  }

  @Test
  void zeroTurnIndexIsAccepted() {
    new DefaultHookContext("sess", 0L, new CancellationToken(), stubModel());
  }

  @Test
  void nullSessionIdRejected() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new DefaultHookContext(null, 0L, new CancellationToken(), stubModel()));
    assertEquals("sessionId must not be null", ex.getMessage());
  }

  @Test
  void blankSessionIdRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new DefaultHookContext("  ", 0L, new CancellationToken(), stubModel()));
    assertEquals("sessionId must not be blank", ex.getMessage());
  }

  @Test
  void emptySessionIdRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new DefaultHookContext("", 0L, new CancellationToken(), stubModel()));
  }

  @Test
  void negativeTurnIndexRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new DefaultHookContext("sess", -1L, new CancellationToken(), stubModel()));
    assertEquals("turnIndex must be non-negative, got -1", ex.getMessage());
  }

  @Test
  void nullCancellationRejected() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new DefaultHookContext("sess", 0L, null, stubModel()));
    assertEquals("cancellation must not be null", ex.getMessage());
  }

  @Test
  void nullModelRejected() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new DefaultHookContext("sess", 0L, new CancellationToken(), null));
    assertEquals("model must not be null", ex.getMessage());
  }
}
