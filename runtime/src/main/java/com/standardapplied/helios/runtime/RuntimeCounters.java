/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

/**
 * A snapshot of what the runtime admitted and refused, per kind of request that holds memory.
 *
 * @param sessions {@code POST /sessions}: admitted when capacity was reserved, rejected when a
 *     session cap refused it
 * @param messages messages and interrupts: admitted when queued, rejected when an upload bound in
 *     {@link RequestLimits} or the session's full steering queue refused it
 * @param eventStreams {@code GET .../events}: admitted when the stream opened, rejected when the
 *     session already had its maximum of streams
 * @param resultPolls {@code GET .../result}: admitted when the poll began waiting, rejected when
 *     the session already had its maximum of polls
 */
public record RuntimeCounters(
    AdmissionCounts sessions,
    AdmissionCounts messages,
    AdmissionCounts eventStreams,
    AdmissionCounts resultPolls) {}
