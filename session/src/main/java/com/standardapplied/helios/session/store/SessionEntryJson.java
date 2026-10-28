/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.store;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.session.loop.SessionJson;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * The one JSON form of a {@link SessionEntry}, which every store that writes JSON uses: {@code
 * {"type":"message","runId":…,"at":…,"message":{…}}} and {@code
 * {"type":"replaced","runId":…,"at":…,"history":[…]}}, {@code at} an ISO-8601 instant and each
 * {@link Message} written by Jackson's defaults. A lone UTF-16 surrogate is escaped, and a decimal
 * reads back as a {@link Double} only when that is its exact value, otherwise as a {@link
 * java.math.BigDecimal}, so an entry reads back as the same JSON value it was written as. A number
 * keeps its value but not always its Java type: a {@code Long} that fits reads back as an {@code
 * Integer}, a {@code Float} as a {@code Double}, a {@code BigDecimal} {@code 1.50} as {@code 1.5}.
 * Reading a {@code type} this version does not know fails, so an entry of a later kind is never
 * skipped.
 */
public final class SessionEntryJson {

  private static final String TYPE = "type";

  private static final String MESSAGE = "message";

  private static final String REPLACED = "replaced";

  private static final String HISTORY = "history";

  private SessionEntryJson() {}

  /**
   * Write an entry.
   *
   * @param entry the entry; non-null
   * @return its JSON
   * @throws NullPointerException if {@code entry} is null
   */
  public static String write(SessionEntry entry) {
    return SessionJson.write(fields(entry));
  }

  /**
   * Read an entry.
   *
   * @param json the JSON {@link #write} wrote; non-null
   * @return the entry
   * @throws NullPointerException if {@code json} is null
   * @throws SessionStoreException if {@code json} is no entry, or of a {@code type} this version
   *     does not know, which the message names
   */
  public static SessionEntry read(String json) {
    Objects.requireNonNull(json, "json must not be null");
    return parsed(() -> entry(SessionJson.ENTRIES.toMap(json)));
  }

  /** One line of a file store: a batch's first sequence number and its entries. */
  record Batch(long seq, List<SessionEntry> entries) {

    Batch {
      if (seq < 1 || entries.isEmpty()) {
        throw new IllegalArgumentException("a batch needs a positive seq and at least one entry");
      }
    }
  }

  /** Write {@code batch} as {@code {"seq":…,"entries":[…]}}. */
  static String write(Batch batch) {
    var line = new LinkedHashMap<String, Object>();
    line.put("seq", batch.seq());
    line.put("entries", batch.entries().stream().map(SessionEntryJson::fields).toList());
    return SessionJson.write(line);
  }

  /**
   * Read a batch {@link #write(Batch)} wrote.
   *
   * @throws SessionStoreException if {@code json} is no batch
   */
  static Batch readBatch(String json) {
    return parsed(
        () -> {
          var line = SessionJson.ENTRIES.toMap(json);
          var entries =
              ((List<?>) line.get("entries")).stream().map(entry -> entry(object(entry))).toList();
          long seq =
              switch (line.get("seq")) {
                case Integer value -> value;
                case Long value -> value;
                case null, default ->
                    throw new IllegalArgumentException("seq is not a 64-bit integer");
              };
          return new Batch(seq, entries);
        });
  }

  private static Map<String, Object> fields(SessionEntry entry) {
    var fields = new LinkedHashMap<String, Object>();
    switch (Objects.requireNonNull(entry, "entry must not be null")) {
      case SessionEntry.MessageAdded added -> {
        stamp(fields, MESSAGE, entry);
        fields.put(MESSAGE, added.message());
      }
      case SessionEntry.HistoryReplaced replaced -> {
        stamp(fields, REPLACED, entry);
        fields.put(HISTORY, replaced.history());
      }
    }
    return fields;
  }

  private static void stamp(Map<String, Object> fields, String type, SessionEntry entry) {
    fields.put(TYPE, type);
    fields.put("runId", entry.runId());
    fields.put("at", entry.at().toString());
  }

  private static SessionEntry entry(Map<String, Object> fields) {
    var type = (String) fields.get(TYPE);
    var runId = (String) fields.get("runId");
    var at = Instant.parse((String) fields.get("at"));
    return switch (Objects.requireNonNull(type, "type is missing")) {
      case MESSAGE -> new SessionEntry.MessageAdded(runId, at, message(fields.get(MESSAGE)));
      case REPLACED ->
          new SessionEntry.HistoryReplaced(
              runId,
              at,
              ((List<?>) fields.get(HISTORY)).stream().map(SessionEntryJson::message).toList());
      default -> throw new SessionStoreException("unknown session entry type '" + type + "'", null);
    };
  }

  private static Message message(Object fields) {
    return SessionJson.ENTRIES.fromMap(object(fields), Message.class);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value) {
    return (Map<String, Object>) Objects.requireNonNull(value, "an object is missing");
  }

  /** What {@code read} returns, any failure to read wrapped in a {@link SessionStoreException}. */
  private static <T> T parsed(Callable<T> read) {
    try {
      return read.call();
    } catch (SessionStoreException e) {
      throw e;
    } catch (Exception e) {
      throw new SessionStoreException("not a session entry: " + e.getClass().getName(), e);
    }
  }
}
