/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import com.standardapplied.helios.gemini.api.InteractionRequest;
import java.util.function.Supplier;

/**
 * The Interactions API request of one turn, the stamp of the prompt it was built under, and, when
 * it continues an interaction, the request sending the whole conversation instead.
 *
 * @param request the request sent first
 * @param promptStamp the stamp of its system instruction and tools
 * @param whole builds the request sending the whole conversation when {@code request} continues an
 *     interaction; {@code null} when it sends the whole conversation already
 */
record InteractionTurn(
    InteractionRequest request, String promptStamp, Supplier<InteractionRequest> whole) {}
