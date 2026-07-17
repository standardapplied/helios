/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence.sql;

/** SQL constants for prompt operations. */
public final class PromptSql {

  private PromptSql() {}

  public static final String NEXT_VERSION =
      """
      SELECT COALESCE(MAX(version), 0) + 1 AS next_version
      FROM %s.helios_prompts
      WHERE name = ?
      """;

  public static final String DEACTIVATE =
      """
      UPDATE %s.helios_prompts SET active = FALSE
      WHERE name = ? AND active = TRUE
      """;

  public static final String INSERT =
      """
      INSERT INTO %s.helios_prompts (id, name, content, version, active, variables, created_at)
      VALUES (CAST(? AS UUID), ?, ?, ?, ?, CAST(? AS TEXT[]), ?)
      """;

  public static final String ACTIVATE =
      """
      UPDATE %s.helios_prompts SET active = TRUE
      WHERE name = ? AND version = ?
      """;

  public static final String RESOLVE_ACTIVE =
      """
      SELECT id, name, content, version, active, variables, created_at
      FROM %s.helios_prompts
      WHERE name = ? AND active = TRUE
      """;

  public static final String RESOLVE_VERSION =
      """
      SELECT id, name, content, version, active, variables, created_at
      FROM %s.helios_prompts
      WHERE name = ? AND version = ?
      """;

  public static final String LIST_VERSIONS =
      """
      SELECT id, name, content, version, active, variables, created_at
      FROM %s.helios_prompts
      WHERE name = ?
      ORDER BY version ASC
      """;
}
