/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

/**
 * Helidon SE service wrapper for the helios-session SDK.
 *
 * <p>Exposes per-session HTTP routes — create / send / interrupt / events (SSE) / delete — that
 * front the {@link com.standardapplied.helios.session.AgentSession AgentSession} surface so a
 * control plane can drive sessions over the wire. The runtime is deployed inside the per-tenant
 * sandbox; the SDK itself remains pure (the session module never depends on Helidon).
 */
package com.standardapplied.helios.runtime;
