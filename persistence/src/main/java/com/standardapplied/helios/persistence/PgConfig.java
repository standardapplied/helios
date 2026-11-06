/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.persistence;

import com.standardapplied.helios.core.common.Redactor;
import io.helidon.dbclient.DbClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Shared configuration for all PostgreSQL persistence classes.
 *
 * @param dbClient the Helidon DbClient for database access
 * @param schema the PostgreSQL schema name (defaults to {@code "public"}). Must match Postgres'
 *     unquoted identifier shape ({@code [A-Za-z_][A-Za-z0-9_]*}, length ≤ 63 — {@code
 *     NAMEDATALEN-1}). The value is interpolated verbatim into SQL by {@link #qualify(String)};
 *     Helidon DbClient cannot parameterise identifiers, so the compact-constructor validator is the
 *     only line of defence against SQL injection if the schema name ever flows from configuration
 *     or external input
 * @param redactor optional {@link Redactor} that {@link PgSpanExporter} applies to each span's
 *     error description, message text and attribute values before writing them. {@code null}
 *     (default) writes them as the session recorded them, suitable for evals and debugging. The
 *     session already redacts the text it puts on its spans against its own secret registry; this
 *     is a second pass, for values that registry does not hold. {@link PgSessionStore} ignores it
 */
public record PgConfig(DbClient dbClient, String schema, Redactor redactor) {

  private static final Pattern VALID_SCHEMA = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");

  /**
   * Validates the configuration and defaults a null {@code schema} to {@code "public"}.
   *
   * @throws NullPointerException if {@code dbClient} is null
   * @throws IllegalArgumentException if {@code schema} is not an unquoted Postgres identifier of at
   *     most 63 characters
   */
  public PgConfig {
    Objects.requireNonNull(dbClient, "dbClient");
    if (schema == null) schema = "public";
    if (!VALID_SCHEMA.matcher(schema).matches()) {
      throw new IllegalArgumentException(
          "schema must match Postgres unquoted identifier shape [A-Za-z_][A-Za-z0-9_]{0,62}; got"
              + " '"
              + schema
              + "'");
    }
  }

  /**
   * Qualify a SQL string by replacing {@code %s} placeholders with the schema name.
   *
   * <p>SQL constants use {@code %s.helios_*} for table references. This method substitutes the
   * configured schema, producing e.g. {@code public.helios_prompts} or {@code lg.helios_prompts}.
   *
   * @param sql the SQL text with a {@code %s} for each schema reference; non-null
   * @return {@code sql} with every {@code %s} replaced by {@link #schema()}
   * @throws NullPointerException if {@code sql} is null
   */
  public String qualify(String sql) {
    return sql.replace("%s", schema);
  }

  /**
   * Apply the configured {@link #redactor()} to {@code value} if set; otherwise return {@code
   * value} unchanged. Returns the same reference when no redactor is configured or nothing matched,
   * so callers cannot detect a per-call allocation when redaction is off (the no-redactor path is
   * the documented default). Callers must treat the return value as read-only — never assume it's a
   * distinct allocation from the input, and never assume the redactor was applied.
   *
   * @param value the text to redact; {@code null} returns {@code null}
   * @return redacted (or unchanged) text
   */
  public String redact(String value) {
    if (redactor == null || value == null) {
      return value;
    }
    return redactor.redact(value).text();
  }

  /**
   * Apply the configured {@link #redactor()} to every value in {@code map} if set; otherwise return
   * {@code map} unchanged. When a redactor is configured the returned map is a fresh {@link
   * LinkedHashMap}; when none is configured the input reference is returned verbatim. The return
   * type does not distinguish the two cases — callers MUST treat the returned map as read-only and
   * MUST NOT mutate it, since the caller may unknowingly be holding the original input. Keys are
   * not redacted — attribute keys are expected to be operator-controlled tag names, not user
   * content.
   *
   * @param map the attribute map; {@code null} returns {@code null}
   * @return the map with redacted values, or {@code map} unchanged when no redactor is configured
   */
  public Map<String, String> redactValues(Map<String, String> map) {
    if (redactor == null || map == null || map.isEmpty()) {
      return map;
    }
    var redacted = new LinkedHashMap<String, String>(map.size());
    for (var entry : map.entrySet()) {
      var v = entry.getValue();
      redacted.put(entry.getKey(), v == null ? null : redactor.redact(v).text());
    }
    return redacted;
  }

  /**
   * Returns an empty builder.
   *
   * @return a builder with no client, the default schema and no redactor
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  /** Builds a {@link PgConfig}. Not thread-safe; use one builder per thread. */
  public static class Builder {

    private DbClient dbClient;
    private String schema;
    private Redactor redactor;

    private Builder() {}

    /**
     * Sets the Helidon client every store and the exporter run their statements through; required.
     * The caller owns it and closes it.
     *
     * @param dbClient the client; {@link #build()} rejects null
     * @return this builder
     */
    public Builder withDbClient(DbClient dbClient) {
      this.dbClient = dbClient;
      return this;
    }

    /**
     * Sets the schema that qualifies every Helios table. Unset or null means {@code "public"}.
     *
     * @param schema an unquoted Postgres identifier of at most 63 characters, or null for {@code
     *     "public"}
     * @return this builder
     */
    public Builder withSchema(String schema) {
      this.schema = schema;
      return this;
    }

    /**
     * Set the {@link Redactor} {@link PgSpanExporter} applies to span text fields and attribute
     * values before persisting. Pass {@code null} to clear a previously-set redactor (default:
     * unset / no-op). This is the only redaction persistence itself applies, on top of the
     * session's own redaction of what it records on its spans.
     *
     * @param redactor the redactor, or null for none
     * @return this builder
     */
    public Builder withRedactor(Redactor redactor) {
      this.redactor = redactor;
      return this;
    }

    /**
     * Builds the configuration.
     *
     * @return the configuration
     * @throws NullPointerException if no client was set
     * @throws IllegalArgumentException if the schema is not an unquoted Postgres identifier of at
     *     most 63 characters
     */
    public PgConfig build() {
      return new PgConfig(dbClient, schema, redactor);
    }
  }
}
