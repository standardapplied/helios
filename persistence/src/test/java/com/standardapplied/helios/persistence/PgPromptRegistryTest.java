/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.common.Ids;
import com.standardapplied.helios.core.prompt.PromptRegistry;
import com.standardapplied.helios.core.test.PromptRegistryContract;
import org.junit.jupiter.api.Test;

class PgPromptRegistryTest extends PromptRegistryContract {

  @Override
  protected PromptRegistry createRegistry() {
    PgTestSupport.truncate();
    return new PgPromptRegistry(PgTestSupport.pgConfig());
  }

  @Test
  void registerNameExceedingColumnLengthThrowsPgException() {
    var longName = "x".repeat(256);
    assertThrows(PgException.class, () -> registry.register(longName, "content"));
  }

  @Test
  void databaseRejectsSecondActiveRowForSameName() {
    registry.register("prompt", "live");

    assertThrows(
        Exception.class,
        () ->
            PgTestSupport.dbClient()
                .execute()
                .dml(
                    """
                    INSERT INTO helios_prompts (id, name, content, version, active, variables,
                        created_at)
                    VALUES (CAST(? AS UUID), 'prompt', 'rogue', 2, TRUE, '{}', now())
                    """,
                    Ids.newId().toString()));

    var stillActive = registry.resolve("prompt");
    assertEquals("live", stillActive.content());
    assertEquals(1, stillActive.version());
  }
}
