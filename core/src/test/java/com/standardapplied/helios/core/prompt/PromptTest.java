/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PromptTest {

  @Test
  void builderDefaults() {
    var prompt = Prompt.newBuilder().withName("test").withContent("Hello {name}!").build();

    assertNotNull(prompt.id());
    assertEquals("test", prompt.name());
    assertEquals("Hello {name}!", prompt.content());
    assertEquals(1, prompt.version());
    assertTrue(prompt.active());
    assertEquals(Set.of("name"), prompt.variables());
    assertNotNull(prompt.createdAt());
  }

  @Test
  void builderExplicitValues() {
    var id = UUID.randomUUID();
    var createdAt = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    var prompt =
        Prompt.newBuilder()
            .withId(id)
            .withName("greeting")
            .withContent("Hi {user}!")
            .withVersion(3)
            .withActive(false)
            .withVariables(Set.of("user", "extra"))
            .withCreatedAt(createdAt)
            .build();

    assertEquals(id, prompt.id());
    assertEquals("greeting", prompt.name());
    assertEquals("Hi {user}!", prompt.content());
    assertEquals(3, prompt.version());
    assertFalse(prompt.active());
    assertEquals(Set.of("user", "extra"), prompt.variables());
    assertEquals(createdAt, prompt.createdAt());
  }

  @Test
  void copyBuilder() {
    var original =
        Prompt.newBuilder().withName("test").withContent("Hello {name}!").withVersion(2).build();

    var copy = Prompt.newBuilder(original).withActive(false).build();

    assertEquals(original.id(), copy.id());
    assertEquals(original.name(), copy.name());
    assertEquals(original.content(), copy.content());
    assertEquals(original.version(), copy.version());
    assertFalse(copy.active());
    assertEquals(original.variables(), copy.variables());
    assertEquals(original.createdAt(), copy.createdAt());
  }

  @Test
  void renderSubstitutesVariables() {
    var prompt =
        Prompt.newBuilder()
            .withName("greeting")
            .withContent("Hello {name}, welcome to {place}!")
            .build();

    var result = prompt.render(Map.of("name", "Alice", "place", "Wonderland"));

    assertEquals("Hello Alice, welcome to Wonderland!", result);
  }

  @Test
  void renderWithMissingVariable() {
    var prompt =
        Prompt.newBuilder()
            .withName("greeting")
            .withContent("Hello {name}, your score is {score}.")
            .build();

    var result = prompt.render(Map.of("name", "Bob"));

    assertEquals("Hello Bob, your score is {score}.", result);
  }

  @Test
  void renderWithEmptyValues() {
    var prompt = Prompt.newBuilder().withName("static").withContent("No variables here.").build();

    assertEquals("No variables here.", prompt.render(Map.of()));
  }

  @Test
  void extractVariablesSingle() {
    assertEquals(Set.of("name"), Prompt.extractVariables("Hello {name}!"));
  }

  @Test
  void extractVariablesMultiple() {
    var vars = Prompt.extractVariables("{greeting}, {name}! Welcome to {place}.");

    assertEquals(Set.of("greeting", "name", "place"), vars);
  }

  @Test
  void extractVariablesDuplicates() {
    var vars = Prompt.extractVariables("{name} and {name} again");

    assertEquals(Set.of("name"), vars);
  }

  @Test
  void extractVariablesNone() {
    assertEquals(Set.of(), Prompt.extractVariables("No variables here."));
  }

  @Test
  void extractVariablesNull() {
    assertEquals(Set.of(), Prompt.extractVariables(null));
  }

  @Test
  void extractVariablesBlank() {
    assertEquals(Set.of(), Prompt.extractVariables("   "));
  }

  @Test
  void extractVariablesEmpty() {
    assertEquals(Set.of(), Prompt.extractVariables(""));
  }

  @Test
  void extractVariablesWithUnderscores() {
    var vars = Prompt.extractVariables("User: {user_name}, ID: {user_id}");

    assertEquals(Set.of("user_name", "user_id"), vars);
  }

  @Test
  void extractVariablesIgnoresNestedBraces() {
    var vars = Prompt.extractVariables("JSON: {\"key\": \"value\"} and {name}");

    assertEquals(Set.of("name"), vars);
  }

  @Test
  void variablesAreImmutable() {
    var prompt = Prompt.newBuilder().withName("test").withContent("Hello {name}!").build();

    var exception =
        org.junit.jupiter.api.Assertions.assertThrows(
            UnsupportedOperationException.class, () -> prompt.variables().add("hack"));

    assertNotNull(exception);
  }

  @Test
  void autoExtractsVariablesWhenNotSet() {
    var prompt = Prompt.newBuilder().withName("test").withContent("{greeting}, {name}!").build();

    assertEquals(Set.of("greeting", "name"), prompt.variables());
  }

  @Test
  void explicitVariablesOverrideAutoExtraction() {
    var prompt =
        Prompt.newBuilder()
            .withName("test")
            .withContent("{greeting}, {name}!")
            .withVariables(Set.of("custom"))
            .build();

    assertEquals(Set.of("custom"), prompt.variables());
  }
}
