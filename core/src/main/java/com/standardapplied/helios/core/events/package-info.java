/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

/**
 * Unified event stream for live observability of Helios primitive runs.
 *
 * <p>This package defines an ordered, append-only stream of {@link
 * com.standardapplied.helios.core.events.HeliosEvent} values that captures everything a live UI
 * would want to render — run lifecycle, iteration boundaries, assistant text and thinking, tool
 * calls, span open/close, sub-agent delegation, compaction. Same event type, same subscription
 * mechanism, regardless of which top-level primitive the user invoked.
 *
 * <p>{@link com.standardapplied.helios.core.events.EventSink} is the observability SPI. The
 * provider-level {@link com.standardapplied.helios.core.model.StreamEvent} channel remains separate
 * at the provider boundary; runtime callers translate it into assistant text / thinking events for
 * sinks.
 *
 * <p>Library consumers tap in by registering an {@link
 * com.standardapplied.helios.core.events.EventSink}. Two reference sinks ship in core: {@link
 * com.standardapplied.helios.core.events.CollectingEventSink} (accumulates into a list for tests
 * and snapshot UIs) and {@link com.standardapplied.helios.core.events.JsonlEventSink} (writes one
 * JSON line per event). Use {@code JsonlEventSink.openMetadataOnly(...)} for privacy-safe
 * operational persistence. Full mode retains content-bearing fields verbatim and is intended only
 * for explicitly sensitive replay/debugging stores.
 */
package com.standardapplied.helios.core.events;
