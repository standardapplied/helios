/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.ask;

import java.util.concurrent.CancellationException;

/**
 * Session-internal bridge between the {@code AskUserQuestion} tool and the session's pending-answer
 * map. The tool calls {@link #ask(AskUserQuestionRequest)} on the agent-loop virtual thread; the
 * gateway emits a {@link ai.singlr.session.QueryEvent.QuestionAsked} event, registers a future
 * keyed by {@code questionId}, then blocks the calling thread on that future. The session's {@code
 * answer(...)} entry-point completes the future from the producer side.
 *
 * <p>The interface is deliberately narrow — production implementations live in {@link
 * ai.singlr.session.AgentSessionImpl}; tests use simple fakes. There is no public factory because
 * the gateway is wired into the agent loop at session-construction time and never exposed to user
 * code directly.
 */
public interface QuestionGateway {

  /**
   * Emit a question and block until the host answers. Re-throws {@link InterruptedException} on
   * thread interruption (the agent-loop virtual thread is the caller, so this surfaces correctly).
   *
   * @param request the question; non-null
   * @return the host's response
   * @throws InterruptedException if the thread is interrupted while waiting
   * @throws CancellationException if the session is cancelled before the host answers
   */
  AskUserQuestionResponse ask(AskUserQuestionRequest request)
      throws InterruptedException, CancellationException;
}
