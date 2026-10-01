/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.hooks;

import ai.singlr.session.QueryEvent;

/**
 * Observe-only hook fired for every {@link QueryEvent} the loop emits. Live observability, progress
 * meters, and audit shipping live here.
 *
 * <p>Unlike the other phase hooks, this one returns {@code void} — it cannot mutate the event,
 * block its delivery, or terminate the session. Misbehaving stream-event hooks must not be able to
 * corrupt the SSE stream.
 *
 * <p>Stream-event hooks run synchronously on the loop's emit thread. Long-running work should be
 * dispatched to a hook-owned executor.
 */
@FunctionalInterface
public non-sealed interface OnStreamEventHook extends Hook {

  /**
   * Observe an event that just shipped to the publisher.
   *
   * @param event the event; non-null
   * @param ctx the per-invocation context; non-null
   */
  void onEvent(QueryEvent event, HookContext ctx);

  @Override
  default String name() {
    return getClass().getSimpleName();
  }
}
