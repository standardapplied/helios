/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

/**
 * The {@code AskUserQuestion} surface — the spec's mechanism for tools, hooks, or the loop to pause
 * and prompt the user before deciding how to proceed.
 *
 * <p>{@link ai.singlr.session.ask.AskUserQuestionRequest} carries the question (id, header, body,
 * options, multi-select flag) and is emitted as a {@code QueryEvent.QuestionAsked} on the session's
 * event stream. The host (UI / CLI / runtime) presents the question and POSTs the user's choice
 * back via {@link ai.singlr.session.AgentSession#answer(String,
 * ai.singlr.session.ask.AskUserQuestionResponse)}. The {@link
 * ai.singlr.session.ask.QuestionGateway} is the per-session bridge between the asking side
 * (typically inside a hook, eg. {@link ai.singlr.session.permissions.DefaultPermissionEvaluator}'s
 * ASK path) and the answering side; the agent loop wires a default {@code SessionQuestionGateway}
 * that blocks the asking virtual thread on a {@link java.util.concurrent.CompletableFuture} until
 * the host answers.
 *
 * <p>{@link ai.singlr.session.ask.AskUserQuestionTool} is the model-facing wrapper: when bound on a
 * session, the LLM can call it directly to ask the user a question mid-turn (used by the
 * "interview" pattern).
 */
package ai.singlr.session.ask;
