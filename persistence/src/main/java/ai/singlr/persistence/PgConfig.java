/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence;

import ai.singlr.core.common.Redactor;
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
 * @param agentId the agent identifier for scoping data, or null when not needed
 * @param redactor optional {@link Redactor} applied by {@link PgTraceStore} and {@link
 *     PgToolCallJournal} to trace/journal text fields before persisting. {@code null} (default)
 *     preserves verbatim capture — the library's standard behaviour, suitable for evals and
 *     debugging. Deployers who want trace-side redaction without wrapping the journal themselves
 *     can pass {@code registry.redactor()} from their session-level {@link
 *     ai.singlr.core.common.SecretRegistry}. Source-level redaction (via {@code CommandGrant},
 *     {@code FilesystemKnowledge}, {@code JShellExecutionProvider}) remains the recommended primary
 *     mitigation; this hook is ergonomic sugar for deployers who need defense-in-depth at the
 *     persistence boundary
 */
public record PgConfig(DbClient dbClient, String schema, String agentId, Redactor redactor) {

  private static final Pattern VALID_SCHEMA = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");

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
   */
  public String qualify(String sql) {
    return sql.replace("%s", schema);
  }

  /**
   * Apply the configured {@link #redactor()} to {@code value} if set; otherwise return {@code
   * value} unchanged. Returns the same reference when no redactor is configured so callers cannot
   * detect a per-call allocation when redaction is off (the no-redactor path is the documented
   * default); when a redactor IS configured, returns a freshly-allocated string. Callers must treat
   * the return value as read-only — never assume it's a distinct allocation from the input, and
   * never assume the redactor was applied.
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

  public static Builder newBuilder() {
    return new Builder();
  }

  /** Builder for PgConfig. */
  public static class Builder {

    private DbClient dbClient;
    private String schema;
    private String agentId;
    private Redactor redactor;

    private Builder() {}

    public Builder withDbClient(DbClient dbClient) {
      this.dbClient = dbClient;
      return this;
    }

    public Builder withSchema(String schema) {
      this.schema = schema;
      return this;
    }

    public Builder withAgentId(String agentId) {
      this.agentId = agentId;
      return this;
    }

    /**
     * Set the {@link Redactor} applied to trace and journal text fields before persisting. Pass
     * {@code null} to clear a previously-set redactor (default: unset / no-op). Source-level
     * redaction at tool boundaries is the recommended primary mitigation; this hook is ergonomic
     * sugar for deployers who want defense-in-depth at the persistence boundary without wrapping
     * the journal themselves.
     */
    public Builder withRedactor(Redactor redactor) {
      this.redactor = redactor;
      return this;
    }

    public PgConfig build() {
      return new PgConfig(dbClient, schema, agentId, redactor);
    }
  }
}
