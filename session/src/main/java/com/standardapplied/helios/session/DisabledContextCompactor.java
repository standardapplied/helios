/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import ai.singlr.core.model.Message;
import ai.singlr.session.loop.SessionState;
import java.util.List;
import java.util.Objects;

/**
 * Singleton {@link ContextCompactor} that never compacts. The loop bypasses the 0.95 trigger when
 * this is configured, so long sessions overflow naturally with whatever the provider returns —
 * usually {@code ErrorDuringExecution}. Use when you want fail-loud behavior or when you've
 * delegated all compaction to a {@code PreModelTurnHook} returning {@code MutateHistory}.
 */
final class DisabledContextCompactor implements ContextCompactor {

  static final DisabledContextCompactor INSTANCE = new DisabledContextCompactor();

  private DisabledContextCompactor() {}

  @Override
  public CompactionResult compact(List<Message> history, SessionState state) {
    Objects.requireNonNull(history, "history must not be null");
    Objects.requireNonNull(state, "state must not be null");
    return CompactionResult.noOp(history);
  }
}
