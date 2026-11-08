/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

/**
 * Durable sessions: a {@link com.standardapplied.helios.session.store.SessionStore} holds each
 * session's conversation, keyed by session id, so a session reopened by id in any process carries
 * on.
 *
 * <p><b>Entry kinds.</b> A store holds an append-only log of {@link
 * com.standardapplied.helios.session.store.SessionEntry entries}, numbered from {@code 1} without
 * gaps: {@code MessageAdded}, one message exactly as the model received it, {@code
 * HistoryReplaced}, the model's whole view after compaction or a {@code MutateHistory} hook, and
 * {@code RunEnded}, the end of a run and the terminal it ended with. A message the session wrote
 * itself carries its {@link com.standardapplied.helios.session.store.MessageOrigin} in its
 * metadata. Nothing is rewritten, so the full transcript stays readable. The system prompt is never
 * stored; it comes from the options that open the session.
 *
 * <p><b>Load rule.</b> {@link com.standardapplied.helios.session.store.StoredSession#fold}: the
 * view of the last {@code HistoryReplaced}, followed by every message added after it; the log's
 * last entry, when it is a {@code RunEnded}, is the session's {@link
 * com.standardapplied.helios.session.store.StoredSession#lastRunEnded()}.
 *
 * <p><b>Write rule.</b> A session appends one batch after each turn its run goes on from, and one
 * at the end of each run: a {@code HistoryReplaced} with the whole history when it was replaced
 * since the last write, otherwise a {@code MessageAdded} per message added, and nothing when
 * nothing is new; the batch at the run's end, never empty, ends with the run's {@code RunEnded}, so
 * the turn that ends a run is stored with its end or not at all.
 *
 * <p><b>Concurrency rule.</b> Each append names the last sequence number its writer read or wrote;
 * a store holding another refuses the whole batch with {@link
 * com.standardapplied.helios.session.store.SessionConflictException}. Serialising the writers of
 * one session id is the caller's job; a session whose write is refused ends its run and closes
 * itself, and reopening it loads what the store holds.
 *
 * <p>{@link com.standardapplied.helios.session.store.InMemorySessionStore} keeps logs in memory,
 * {@link com.standardapplied.helios.session.store.FileSessionStore} in JSON-lines files, in the
 * form of {@link com.standardapplied.helios.session.store.SessionEntryJson}.
 */
package com.standardapplied.helios.session.store;
