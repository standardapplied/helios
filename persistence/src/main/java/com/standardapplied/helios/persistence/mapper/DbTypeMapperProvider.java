/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence.mapper;

import io.helidon.common.mapper.Mapper;
import io.helidon.common.mapper.spi.MapperProvider;
import io.helidon.dbclient.DbRow;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Helidon mapper provider for database types used by the persistence module. */
public class DbTypeMapperProvider implements MapperProvider {

  private static final Logger LOG = Logger.getLogger(DbTypeMapperProvider.class.getName());

  private static final Mapper<Timestamp, OffsetDateTime> TIMESTAMP_TO_OFFSET =
      ts -> ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);

  @Override
  public ProviderResponse mapper(Class<?> sourceClass, Class<?> targetClass, String qualifier) {
    if (sourceClass == Timestamp.class && targetClass == OffsetDateTime.class) {
      return new ProviderResponse(Support.SUPPORTED, TIMESTAMP_TO_OFFSET);
    }
    return ProviderResponse.unsupported();
  }

  /**
   * Reads a PostgreSQL TEXT[] column as a set of strings.
   *
   * @param row the database row
   * @param columnName the array column name
   * @return the values as an immutable set, or an empty set if the column is empty or null
   */
  public static Set<String> readStringSet(DbRow row, String columnName) {
    try {
      var arrayObj = row.column(columnName).get(Object.class);
      if (arrayObj == null) {
        return Set.of();
      }
      if (arrayObj instanceof Array sqlArray) {
        var array = (String[]) sqlArray.getArray();
        return array != null && array.length > 0 ? Set.copyOf(Arrays.asList(array)) : Set.of();
      }
      if (arrayObj instanceof String[] stringArray) {
        return stringArray.length > 0 ? Set.copyOf(Arrays.asList(stringArray)) : Set.of();
      }
      return Set.of();
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Failed to read string set from column: " + columnName, e);
      return Set.of();
    }
  }
}
