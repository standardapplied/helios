/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */
package ai.singlr.examples.codeact;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.Model;
import ai.singlr.core.model.ModelConfig;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.examples.codeact.CodeActDemoMain.EdcInput;
import ai.singlr.examples.codeact.CodeActDemoMain.EdcRow;
import ai.singlr.examples.codeact.CodeActDemoMain.SdtmMapping;
import ai.singlr.gemini.GeminiModelId;
import ai.singlr.gemini.GeminiProvider;
import ai.singlr.repl.codeact.CodeActPreset;
import ai.singlr.session.AgentSession;
import ai.singlr.session.SessionLimits;
import ai.singlr.session.SessionOptions;
import ai.singlr.session.UserMessage;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Integration test for the CodeAct demo against Gemini 3.1 Pro. Skipped when {@code GEMINI_API_KEY}
 * is unset.
 *
 * <p>Assertions describe the framework guarantee given a cooperating model — the typed {@code
 * runBlocking} path returns a non-null {@link SdtmMapping} whose row count matches the input. We
 * intentionally do not pin specific (domain, variable) values: free-form code synthesis is not
 * fully deterministic even on Pro.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
final class CodeActDemoIntegrationTest {

  private static Model model;

  @BeforeAll
  static void setUp() {
    var apiKey = System.getenv("GEMINI_API_KEY");
    var config = ModelConfig.newBuilder().withApiKey(apiKey).build();
    model = new GeminiProvider().create(GeminiModelId.GEMINI_3_5_FLASH.id(), config);
  }

  @AfterAll
  static void tearDown() throws Exception {
    if (model != null) {
      model.close();
    }
  }

  @Test
  void demoProducesValidSdtmMapping() throws Exception {
    var input =
        new EdcInput(
            List.of(
                new EdcRow("DEMOG.AGE", "42"),
                new EdcRow("DEMOG.SEX", "F"),
                new EdcRow("VS.SYSBP", "120")));

    var options =
        SessionOptions.newBuilder()
            .withModel(model)
            .apply(CodeActPreset.typed(EdcInput.class, SdtmMapping.class, input))
            .withLimits(
                SessionLimits.newBuilder()
                    .withMaxTurns(15)
                    .withToolTimeoutDefault(Duration.ofMinutes(2))
                    .build())
            .build();

    try (var session = AgentSession.create(options)) {
      var prompt = CodeActDemoMain.buildPrompt();
      var mapping =
          session.runBlocking(UserMessage.text(prompt), OutputSchema.of(SdtmMapping.class));

      assertNotNull(mapping, "typed runBlocking should produce a non-null SdtmMapping");
      assertNotNull(mapping.rows(), "mapping rows list should not be null");
      assertFalse(mapping.rows().isEmpty(), "mapping should contain at least one row");
      assertTrue(
          mapping.rows().size() >= input.rows().size() - 1,
          () ->
              "expected mapping row count near "
                  + input.rows().size()
                  + ", got "
                  + mapping.rows().size());
      mapping
          .rows()
          .forEach(
              row -> {
                assertNotNull(row.domain(), "SDTM domain should be set");
                assertNotNull(row.variable(), "SDTM variable should be set");
                assertNotNull(row.value(), "SDTM value should be set");
                assertFalse(row.domain().isBlank(), "SDTM domain should be non-blank");
                assertFalse(row.variable().isBlank(), "SDTM variable should be non-blank");
                assertFalse(row.value().isBlank(), "SDTM value should be non-blank");
              });
    }
  }
}
