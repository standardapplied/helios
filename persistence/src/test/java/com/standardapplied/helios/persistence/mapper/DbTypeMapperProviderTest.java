/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.persistence.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.helidon.dbclient.DbColumn;
import io.helidon.dbclient.DbRow;
import java.lang.reflect.Proxy;
import java.sql.Array;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

class DbTypeMapperProviderTest {

  private static final List<String> NAMES =
      List.of("zeta", "alpha", "mu", "beta", "omega", "gamma", "kappa", "delta");

  @Test
  void sqlArrayIsReadInArrayOrder() {
    assertEquals(NAMES, read(() -> array(NAMES.toArray(String[]::new))));
  }

  @Test
  void stringArrayIsReadInArrayOrder() {
    assertEquals(NAMES, read(() -> NAMES.toArray(String[]::new)));
  }

  @Test
  void nullColumnSqlArrayOrOtherValueIsEmpty() {
    assertTrue(read(() -> null).isEmpty());
    assertTrue(read(() -> array(null)).isEmpty());
    assertTrue(read(() -> 42).isEmpty());
  }

  @Test
  void unreadableColumnIsEmpty() {
    assertTrue(
        read(() -> {
              throw new SQLException("column gone");
            })
            .isEmpty());
  }

  private static List<String> read(Callable<Object> columnValue) {
    DbColumn column = proxy(DbColumn.class, columnValue);
    DbRow row = proxy(DbRow.class, () -> column);
    return List.copyOf(DbTypeMapperProvider.readStringSet(row, "variables"));
  }

  private static Array array(String[] values) {
    return proxy(Array.class, () -> values);
  }

  /** A proxy whose every method answers with {@code answer}: the one method each test calls. */
  private static <T> T proxy(Class<T> type, Callable<?> answer) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(), new Class<?>[] {type}, (self, method, args) -> answer.call()));
  }
}
