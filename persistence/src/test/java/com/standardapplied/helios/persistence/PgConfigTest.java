/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.core.common.SecretRegistry;
import org.junit.jupiter.api.Test;

class PgConfigTest {

  @Test
  void defaultSchemaIsPublic() {
    var config = PgConfig.newBuilder().withDbClient(PgTestSupport.dbClient()).build();

    assertEquals("public", config.schema());
  }

  @Test
  void customSchema() {
    var config =
        PgConfig.newBuilder().withDbClient(PgTestSupport.dbClient()).withSchema("lg").build();

    assertEquals("lg", config.schema());
  }

  @Test
  void qualifyWithDefaultSchema() {
    var config = PgConfig.newBuilder().withDbClient(PgTestSupport.dbClient()).build();

    assertEquals(
        "SELECT * FROM public.helios_prompts WHERE name = ?",
        config.qualify("SELECT * FROM %s.helios_prompts WHERE name = ?"));
  }

  @Test
  void qualifyWithCustomSchema() {
    var config =
        PgConfig.newBuilder().withDbClient(PgTestSupport.dbClient()).withSchema("myapp").build();

    assertEquals(
        "SELECT * FROM myapp.helios_prompts WHERE name = ?",
        config.qualify("SELECT * FROM %s.helios_prompts WHERE name = ?"));
  }

  @Test
  void qualifyMultiplePlaceholders() {
    var config =
        PgConfig.newBuilder().withDbClient(PgTestSupport.dbClient()).withSchema("lg").build();

    assertEquals(
        "INSERT INTO lg.helios_spans SELECT * FROM lg.helios_traces",
        config.qualify("INSERT INTO %s.helios_spans SELECT * FROM %s.helios_traces"));
  }

  @Test
  void agentIdDefaultsToNull() {
    var config = PgConfig.newBuilder().withDbClient(PgTestSupport.dbClient()).build();

    assertNull(config.agentId());
  }

  @Test
  void agentIdIsStored() {
    var config =
        PgConfig.newBuilder()
            .withDbClient(PgTestSupport.dbClient())
            .withAgentId("my-agent")
            .build();

    assertEquals("my-agent", config.agentId());
  }

  @Test
  void nullDbClientThrows() {
    assertThrows(NullPointerException.class, () -> PgConfig.newBuilder().build());
  }

  // Schema name is interpolated verbatim into SQL via String.replace("%s", schema). Helidon
  // DbClient cannot parameterise identifiers, so the validator at construction time is the only
  // line of defence against SQL injection through a config-driven schema name. The valid shape is
  // Postgres' unquoted identifier: leading letter/underscore, [letters digits underscore] tail,
  // length ≤ 63 (NAMEDATALEN-1).

  @Test
  void schemaWithSqlMetacharsRejected() {
    var builder = PgConfig.newBuilder().withDbClient(PgTestSupport.dbClient());
    assertThrows(
        IllegalArgumentException.class,
        () -> builder.withSchema("public; DROP TABLE helios_traces;--").build());
  }

  @Test
  void schemaWithSpaceRejected() {
    var builder = PgConfig.newBuilder().withDbClient(PgTestSupport.dbClient());
    assertThrows(IllegalArgumentException.class, () -> builder.withSchema("my schema").build());
  }

  @Test
  void schemaWithLeadingDigitRejected() {
    var builder = PgConfig.newBuilder().withDbClient(PgTestSupport.dbClient());
    assertThrows(IllegalArgumentException.class, () -> builder.withSchema("9bad").build());
  }

  @Test
  void schemaTooLongRejected() {
    var builder = PgConfig.newBuilder().withDbClient(PgTestSupport.dbClient());
    var sixtyFour = "a".repeat(64);
    assertThrows(IllegalArgumentException.class, () -> builder.withSchema(sixtyFour).build());
  }

  @Test
  void schemaWithUnderscoreAndDigitsAccepted() {
    var config =
        PgConfig.newBuilder().withDbClient(PgTestSupport.dbClient()).withSchema("_my_2nd").build();
    assertEquals("_my_2nd", config.schema());
  }

  // Opt-in trace-side redaction. Default unset = no-op = current verbatim behavior. Set to a
  // Redactor and PgConfig.redact(text) scrubs against it before the persistence call sites pass
  // the value to JsonbMapper / DbClient.

  @Test
  void redactorDefaultsToNull() {
    var config = PgConfig.newBuilder().withDbClient(PgTestSupport.dbClient()).build();
    assertNull(config.redactor(), "fresh config has no redactor — current verbatim behavior");
  }

  @Test
  void redactPassesValueThroughWhenNoRedactorConfigured() {
    var config = PgConfig.newBuilder().withDbClient(PgTestSupport.dbClient()).build();
    var raw = "Authorization: Bearer ghp_supersecret_12345678";
    assertSame(
        raw,
        config.redact(raw),
        "default redact() must be a no-op — returns the same String reference so callers can't"
            + " detect a per-call allocation cost");
  }

  @Test
  void redactReturnsNullForNullInput() {
    var config = PgConfig.newBuilder().withDbClient(PgTestSupport.dbClient()).build();
    assertNull(config.redact(null));
  }

  @Test
  void redactAppliesConfiguredRedactor() {
    var registry = new SecretRegistry();
    registry.register("GH_TOKEN", "ghp_supersecret_12345678");
    var config =
        PgConfig.newBuilder()
            .withDbClient(PgTestSupport.dbClient())
            .withRedactor(registry.redactor())
            .build();
    assertNotNull(config.redactor());

    var raw = "Authorization: Bearer ghp_supersecret_12345678";
    var redacted = config.redact(raw);
    assertEquals(
        "Authorization: Bearer <redacted:GH_TOKEN>",
        redacted,
        "value should pass through the configured redactor; got: " + redacted);
  }

  @Test
  void withRedactorAcceptsNullToClear() {
    var registry = new SecretRegistry();
    registry.register("GH_TOKEN", "ghp_supersecret_12345678");
    var config =
        PgConfig.newBuilder()
            .withDbClient(PgTestSupport.dbClient())
            .withRedactor(registry.redactor())
            .withRedactor(null)
            .build();
    assertNull(config.redactor());
    assertSame("raw", config.redact("raw"));
  }
}
