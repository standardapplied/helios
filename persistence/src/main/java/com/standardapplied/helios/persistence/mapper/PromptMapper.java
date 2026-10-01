/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence.mapper;

import ai.singlr.core.prompt.Prompt;
import io.helidon.dbclient.DbRow;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/** Maps Helidon {@link DbRow} results to {@link Prompt} records. */
public final class PromptMapper {

  private PromptMapper() {}

  /** Maps a single database row to a Prompt. */
  public static Prompt map(DbRow row) {
    return Prompt.newBuilder()
        .withId(row.column("id").get(UUID.class))
        .withName(row.column("name").getString())
        .withContent(row.column("content").getString())
        .withVersion(row.column("version").getInt())
        .withActive(row.column("active").get(Boolean.class))
        .withVariables(DbTypeMapperProvider.readStringSet(row, "variables"))
        .withCreatedAt(row.column("created_at").get(OffsetDateTime.class))
        .build();
  }

  /** Maps a stream of database rows to an immutable list of Prompts. */
  public static List<Prompt> mapAll(Stream<DbRow> rows) {
    return rows.map(PromptMapper::map).toList();
  }

  /**
   * Converts a set of variable names to a PostgreSQL TEXT[] literal.
   *
   * @param variables the variable names
   * @return a PostgreSQL array literal, e.g. {@code {name,place}}
   */
  public static String toArrayLiteral(Set<String> variables) {
    if (variables == null || variables.isEmpty()) {
      return "{}";
    }
    var sb = new StringBuilder("{");
    var first = true;
    for (var v : variables) {
      if (!first) sb.append(',');
      sb.append('"');
      sb.append(v.replace("\\", "\\\\").replace("\"", "\\\""));
      sb.append('"');
      first = false;
    }
    sb.append('}');
    return sb.toString();
  }
}
