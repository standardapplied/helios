/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.anthropic.api.ContentDelta;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Role;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The code execution container a turn's searches ran in, carried to the requests after it so the
 * files its code wrote survive: a request without it starts a new, empty container. A turn records
 * the container's id and {@code expires_at}; a {@code pause_turn} continuation sends the id back,
 * and so does a later request while {@code expires_at} has not passed. The API restores a
 * checkpointed container after that time too, but an id it no longer knows fails every code step of
 * the turn, and {@code expires_at} is the only expiry it reports.
 */
final class CodeContainer {

  /** Metadata key carrying the id of the container the turn ran code in. */
  static final String ID_KEY = "anthropic.container";

  /** Metadata key carrying that container's {@code expires_at}, an ISO-8601 instant. */
  static final String EXPIRES_AT_KEY = "anthropic.containerExpiresAt";

  private CodeContainer() {}

  /** Records {@code container} in {@code metadata}, unless it is null or lacks its id or expiry. */
  static void record(Map<String, String> metadata, ContentDelta.Container container) {
    if (container != null && container.id() != null && container.expiresAt() != null) {
      metadata.put(ID_KEY, container.id());
      metadata.put(EXPIRES_AT_KEY, container.expiresAt().toString());
    }
  }

  /** Copies the container {@code from} records into {@code to}, unless {@code to} records one. */
  static void carry(Map<String, String> from, Map<String, String> to) {
    if (!to.containsKey(ID_KEY) && from.containsKey(ID_KEY)) {
      to.put(ID_KEY, from.get(ID_KEY));
      to.put(EXPIRES_AT_KEY, from.get(EXPIRES_AT_KEY));
    }
  }

  /**
   * The id of the container the latest assistant turn of {@code messages} that recorded one ran in,
   * or {@code null} when none did or its {@code expires_at} is not after {@code now}.
   */
  static String reusable(List<Message> messages, Instant now) {
    for (var message : messages.reversed()) {
      var metadata = message.metadata();
      if (message.role() == Role.ASSISTANT && metadata != null && metadata.containsKey(ID_KEY)) {
        return now.isBefore(Instant.parse(metadata.get(EXPIRES_AT_KEY)))
            ? metadata.get(ID_KEY)
            : null;
      }
    }
    return null;
  }
}
