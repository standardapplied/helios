/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

/**
 * The agent-loop machinery — the components {@link ai.singlr.session.AgentSessionImpl} assembles to
 * drive a session from first user message to terminal {@link ai.singlr.session.ResultMessage}.
 *
 * <p>Composition is deliberately wide rather than deep:
 *
 * <ul>
 *   <li>{@link ai.singlr.session.loop.AgentLoop} is the per-session orchestrator: it drains the
 *       steering queue, fires hooks at every lifecycle phase, runs one turn at a time, and
 *       classifies the loop's stop condition after each turn.
 *   <li>{@link ai.singlr.session.loop.TurnRunner} executes one model turn end-to-end: streams the
 *       provider's chunks through a {@link ai.singlr.session.loop.TurnSubscriber}, dispatches any
 *       tool calls via {@link ai.singlr.session.loop.ToolDispatch}, accumulates usage + cost on the
 *       session, fires {@code PreModelTurn} / {@code PostModelTurn} hooks, and returns a {@link
 *       ai.singlr.session.loop.TurnOutcome}.
 *   <li>{@link ai.singlr.session.loop.StopClassifier} is the pure function that maps a turn outcome
 *       + session state + limits to an optional terminal {@code ResultMessage}.
 *   <li>{@link ai.singlr.session.loop.SessionState} is the loop's shared mutable state (history,
 *       usage / cost / elapsed accumulators, cancellation token, turn index, terminal slot).
 *       Single-writer (the loop) with thread-safe accumulators so subscriber threads can read.
 *   <li>{@link ai.singlr.session.loop.EventEmitter} is the convenience shim that builds {@link
 *       ai.singlr.session.QueryEvent} records and pushes them to the publisher.
 * </ul>
 *
 * <p>None of these types are part of the published SDK surface — they live here so the {@code
 * AgentSessionImpl} composition stays mechanical and each piece is independently testable.
 */
package ai.singlr.session.loop;
