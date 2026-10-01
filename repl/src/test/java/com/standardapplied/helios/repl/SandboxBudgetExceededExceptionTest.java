/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class SandboxBudgetExceededExceptionTest {

  @Test
  void carriesKindLimitAndActual() {
    var ex =
        new SandboxBudgetExceededException(
            SandboxBudgetExceededException.BudgetKind.LLM_CALLS, 50, 51, "exhausted");
    assertSame(SandboxBudgetExceededException.BudgetKind.LLM_CALLS, ex.kind());
    assertEquals(50, ex.limit());
    assertEquals(51, ex.actual());
    assertEquals("exhausted", ex.getMessage());
  }

  @Test
  void isRuntimeException() {
    var ex =
        new SandboxBudgetExceededException(
            SandboxBudgetExceededException.BudgetKind.LLM_CALLS, 1, 2, "boom");
    assertEquals(RuntimeException.class, ex.getClass().getSuperclass());
  }
}
