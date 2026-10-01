/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.hooks;

import ai.singlr.core.model.Model;
import ai.singlr.core.runtime.CancellationToken;

/**
 * Per-invocation context passed to every {@link Hook} call. Carries the session-wide handles a hook
 * may need to make a decision — session id, current turn index, cancellation token, and the model
 * itself (for hooks that want to call out for judgment).
 *
 * <p>Phase 2 ships the minimum field set the existing subsystems provide. Subsequent phases extend
 * the contract: Phase 3 adds {@code workspaceRoot()}, Phase 4 adds {@code memory()}, Phase 5 adds
 * {@code execution()}, Phase 7 adds {@code audit()}. Internal users only, so additions break the
 * interface freely.
 *
 * <h2>Thread-safety</h2>
 *
 * Implementations are immutable; safe to pass to arbitrary hook code without defensive copying.
 */
public interface HookContext {

  /**
   * The session that produced this hook invocation.
   *
   * @return non-blank session id
   */
  String sessionId();

  /**
   * The agent-loop turn index at the moment the hook is firing. For pre-turn hooks this is the
   * index of the turn that is about to start; for post-turn hooks it is the index of the turn that
   * just ended.
   *
   * @return non-negative turn index
   */
  long turnIndex();

  /**
   * The session's cancellation token. Hooks performing long-running work — e.g. a devil's-advocate
   * model call — should poll this at safe points.
   *
   * @return non-null token
   */
  CancellationToken cancellation();

  /**
   * The model the session loop is driving. Hooks may call {@code model.chat(...)} for arbitrary
   * judgment work; the call counts against the session's overall budget.
   *
   * @return non-null model
   */
  Model model();
}
