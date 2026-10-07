/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.repl.codeact;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.standardapplied.helios.core.test.ChildJvm;
import com.standardapplied.helios.core.test.DeclarationOrderFixture;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The CodeAct and RLM system prompts for {@link DeclarationOrderFixture.Dossier}: byte-identical in
 * every JVM, with the output fields in declaration order.
 */
class PromptDeclarationOrderTest {

  private static final String BETWEEN_PROMPTS = "\n=== RLM ===\n";

  /** Prints the CodeAct prompt, then the RLM prompt. */
  static final class Probe {
    public static void main(String[] args) {
      System.out.print(prompts());
    }
  }

  @Test
  void codeActAndRlmPromptsAreTheSameInEveryJvmAndInDeclaredOrder(@TempDir Path dir) {
    var prompts = ChildJvm.sameInEveryJvm(dir, Probe.class).split(BETWEEN_PROMPTS);

    assertEquals(2, prompts.length);
    for (var prompt : prompts) {
      DeclarationOrderFixture.assertInOrder(prompt, DeclarationOrderFixture.DOSSIER_FIELDS);
    }
  }

  private static String prompts() {
    var schema = DeclarationOrderFixture.schema();
    return CodeActStrategy.buildSystemPrompt(schema, schema, 5000, List.of(), List.of(), "")
        + BETWEEN_PROMPTS
        + RlmStrategy.buildSystemPrompt(
            schema, schema, 5000, OptionalInt.empty(), List.of(), List.of(), "");
  }
}
