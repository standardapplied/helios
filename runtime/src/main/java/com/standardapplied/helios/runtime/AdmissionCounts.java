/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

/**
 * How many requests of one kind the runtime admitted and how many it refused for a limit, since it
 * started.
 *
 * @param admitted requests that passed admission
 * @param rejected requests refused because a limit was reached
 */
public record AdmissionCounts(long admitted, long rejected) {}
