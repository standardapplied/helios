/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence.sql;

/** SQL constants for annotation operations. */
public final class AnnotationSql {

  private AnnotationSql() {}

  private static final String COLUMNS =
      "id, subject_id, facet, label, author_kind, author_id, rating, comment, metadata, "
          + "created_at, updated_at";

  private static final String VALUES =
      "CAST(? AS UUID), CAST(? AS UUID), ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?, ?";

  public static final String INSERT =
      "INSERT INTO %s.helios_annotations (" + COLUMNS + ")\nVALUES (" + VALUES + ")";

  public static final String UPSERT =
      "INSERT INTO %s.helios_annotations ("
          + COLUMNS
          + ")\nVALUES ("
          + VALUES
          + ")\n"
          + """
          ON CONFLICT (subject_id, COALESCE(facet, ''), label, author_id) WHERE author_id IS NOT NULL
          DO UPDATE SET
              author_kind = EXCLUDED.author_kind,
              rating = EXCLUDED.rating,
              comment = EXCLUDED.comment,
              metadata = EXCLUDED.metadata,
              updated_at = EXCLUDED.updated_at
          """;

  public static final String FIND_BY_SUBJECT =
      "SELECT "
          + COLUMNS
          + "\nFROM %s.helios_annotations\nWHERE subject_id = CAST(? AS UUID)\n"
          + "ORDER BY created_at ASC";

  public static final String FIND_BY_SUBJECTS_PREFIX =
      "SELECT " + COLUMNS + "\nFROM %s.helios_annotations\nWHERE subject_id IN (";

  public static final String FIND_BY_SUBJECTS_SUFFIX = ")\nORDER BY subject_id, created_at ASC";

  public static final String LIST_PREFIX = "SELECT " + COLUMNS + "\nFROM %s.helios_annotations\n";

  public static final String LIST_SUFFIX = "ORDER BY created_at ASC\nLIMIT :limit OFFSET :offset";
}
