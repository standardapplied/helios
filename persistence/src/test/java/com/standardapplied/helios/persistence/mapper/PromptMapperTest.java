/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PromptMapperTest {

  @Test
  void toArrayLiteralWithNull() {
    assertEquals("{}", PromptMapper.toArrayLiteral(null));
  }

  @Test
  void toArrayLiteralWithEmptySet() {
    assertEquals("{}", PromptMapper.toArrayLiteral(Set.of()));
  }

  @Test
  void toArrayLiteralWithSimpleValues() {
    var vars = new LinkedHashSet<>(List.of("name", "place"));
    assertEquals("{\"name\",\"place\"}", PromptMapper.toArrayLiteral(vars));
  }

  @Test
  void toArrayLiteralEscapesCommas() {
    var vars = new LinkedHashSet<>(List.of("a,b"));
    assertEquals("{\"a,b\"}", PromptMapper.toArrayLiteral(vars));
  }

  @Test
  void toArrayLiteralEscapesDoubleQuotes() {
    var vars = new LinkedHashSet<>(List.of("say\"hello\""));
    assertEquals("{\"say\\\"hello\\\"\"}", PromptMapper.toArrayLiteral(vars));
  }

  @Test
  void toArrayLiteralEscapesBackslashes() {
    var vars = new LinkedHashSet<>(List.of("path\\to"));
    assertEquals("{\"path\\\\to\"}", PromptMapper.toArrayLiteral(vars));
  }

  @Test
  void toArrayLiteralSingleValue() {
    var vars = Set.of("name");
    assertEquals("{\"name\"}", PromptMapper.toArrayLiteral(vars));
  }
}
