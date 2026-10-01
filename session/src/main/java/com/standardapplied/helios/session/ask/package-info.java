/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

/**
 * The {@code AskUserQuestion} surface — the spec's mechanism for tools, hooks, or the loop to pause
 * and prompt the user before deciding how to proceed.
 *
 * <p>{@link com.standardapplied.helios.session.ask.AskUserQuestionRequest} carries the question
 * (id, header, body, options, multi-select flag) and is emitted as a {@code
 * QueryEvent.QuestionAsked} on the session's event stream. The host (UI / CLI / runtime) presents
 * the question and POSTs the user's choice back via {@link
 * com.standardapplied.helios.session.AgentSession#answer(String,
 * com.standardapplied.helios.session.ask.AskUserQuestionResponse)}. The {@link
 * com.standardapplied.helios.session.ask.QuestionGateway} is the per-session bridge between the
 * asking side (typically inside a hook, eg. {@link
 * com.standardapplied.helios.session.permissions.DefaultPermissionEvaluator}'s ASK path) and the
 * answering side; the agent loop wires a default {@code SessionQuestionGateway} that blocks the
 * asking virtual thread on a {@link java.util.concurrent.CompletableFuture} until the host answers.
 *
 * <p>{@link com.standardapplied.helios.session.ask.AskUserQuestionTool} is the model-facing
 * wrapper: when bound on a session, the LLM can call it directly to ask the user a question
 * mid-turn (used by the "interview" pattern).
 */
package com.standardapplied.helios.session.ask;
