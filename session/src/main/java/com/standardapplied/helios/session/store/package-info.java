/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

/**
 * Durable sessions: a {@link com.standardapplied.helios.session.store.SessionStore} holds each
 * session's conversation, keyed by session id, so a session reopened by id in any process carries
 * on.
 *
 * <p><b>Entry kinds.</b> A store holds an append-only log of {@link
 * com.standardapplied.helios.session.store.SessionEntry entries}, numbered from {@code 1} without
 * gaps: {@code MessageAdded}, one message exactly as the model received it, and {@code
 * HistoryReplaced}, the model's whole view after compaction or a {@code MutateHistory} hook.
 * Nothing is rewritten, so the full transcript stays readable. The system prompt is never stored;
 * it comes from the options that open the session.
 *
 * <p><b>Load rule.</b> {@link com.standardapplied.helios.session.store.StoredSession#fold}: the
 * view of the last {@code HistoryReplaced}, followed by every message added after it.
 *
 * <p><b>Write rule.</b> A session appends one batch at the end of each turn's tool round and one at
 * the end of each run: a {@code HistoryReplaced} with the whole history when it was replaced since
 * the last write, otherwise a {@code MessageAdded} per message added, and nothing when nothing is
 * new.
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
