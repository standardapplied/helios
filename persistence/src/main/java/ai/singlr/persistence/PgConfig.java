/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence;

import io.helidon.dbclient.DbClient;
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
 */
public record PgConfig(DbClient dbClient, String schema, String agentId) {

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

  public static Builder newBuilder() {
    return new Builder();
  }

  /** Builder for PgConfig. */
  public static class Builder {

    private DbClient dbClient;
    private String schema;
    private String agentId;

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

    public PgConfig build() {
      return new PgConfig(dbClient, schema, agentId);
    }
  }
}
