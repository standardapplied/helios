/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.runtime;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.common.Ids;
import com.standardapplied.helios.core.test.ToolCallJournalContract;
import org.junit.jupiter.api.Test;

class InMemoryToolCallJournalTest extends ToolCallJournalContract {

  @Override
  protected ToolCallJournal createJournal() {
    return new InMemoryToolCallJournal();
  }

  @Test
  void duplicateStartThrows() {
    var runId = Ids.newId();
    var first = started(runId, "c1", "send");
    journal.start(first);
    var second = started(runId, "c1", "send");
    assertThrows(IllegalStateException.class, () -> journal.start(second));
  }
}
