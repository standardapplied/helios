/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolChoiceTest {

  @Test
  void autoReturnsSingleton() {
    var choice1 = ToolChoice.auto();
    var choice2 = ToolChoice.auto();

    assertSame(choice1, choice2);
    assertInstanceOf(ToolChoice.Auto.class, choice1);
  }

  @Test
  void anyReturnsSingleton() {
    var choice1 = ToolChoice.any();
    var choice2 = ToolChoice.any();

    assertSame(choice1, choice2);
    assertInstanceOf(ToolChoice.Any.class, choice1);
  }

  @Test
  void noneReturnsSingleton() {
    var choice1 = ToolChoice.none();
    var choice2 = ToolChoice.none();

    assertSame(choice1, choice2);
    assertInstanceOf(ToolChoice.None.class, choice1);
  }

  @Test
  void requiredWithSingleTool() {
    var choice = ToolChoice.required("get_weather");

    assertInstanceOf(ToolChoice.Required.class, choice);
    var required = (ToolChoice.Required) choice;
    assertEquals(Set.of("get_weather"), required.allowedTools());
  }

  @Test
  void requiredWithMultipleTools() {
    var choice = ToolChoice.required("get_weather", "search_web", "run_code");

    var required = (ToolChoice.Required) choice;
    assertEquals(Set.of("get_weather", "search_web", "run_code"), required.allowedTools());
  }

  @Test
  void requiredWithEmptyToolsThrows() {
    assertThrows(IllegalArgumentException.class, () -> ToolChoice.required());
  }

  @Test
  void patternMatchingWorks() {
    var auto = ToolChoice.auto();
    var any = ToolChoice.any();
    var none = ToolChoice.none();
    var required = ToolChoice.required("tool1");

    assertEquals("auto", describe(auto));
    assertEquals("any", describe(any));
    assertEquals("none", describe(none));
    assertTrue(describe(required).startsWith("required:"));
  }

  private String describe(ToolChoice choice) {
    return switch (choice) {
      case ToolChoice.Auto _ -> "auto";
      case ToolChoice.Any _ -> "any";
      case ToolChoice.None _ -> "none";
      case ToolChoice.Required r -> "required:" + r.allowedTools();
    };
  }
}
