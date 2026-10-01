/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DurabilityTest {

  @Test
  void inMemoryFactoryReturnsFreshInstancesEachCall() {
    var first = Durability.inMemory();
    var second = Durability.inMemory();
    assertNotSame(first.runStore(), second.runStore());
    assertNotSame(first.toolCallJournal(), second.toolCallJournal());
    assertEquals(UnsafeResumePolicy.FAIL_LOUD, first.unsafeResumePolicy());
    assertTrue(first.idempotentToolsOverride().isEmpty());
  }

  @Test
  void ofUsesDefaults() {
    var store = new InMemoryRunStore();
    var journal = new InMemoryToolCallJournal();
    var d = Durability.of(store, journal);
    assertEquals(store, d.runStore());
    assertEquals(journal, d.toolCallJournal());
    assertEquals(UnsafeResumePolicy.FAIL_LOUD, d.unsafeResumePolicy());
    assertTrue(d.idempotentToolsOverride().isEmpty());
  }

  @Test
  void builderHappyPath() {
    var store = new InMemoryRunStore();
    var journal = new InMemoryToolCallJournal();
    var d =
        Durability.newBuilder()
            .withRunStore(store)
            .withToolCallJournal(journal)
            .withUnsafeResumePolicy(UnsafeResumePolicy.AUTO_FAIL_AND_CONTINUE)
            .withIdempotentToolOverride("get_x", true)
            .withIdempotentToolOverride("send", false)
            .build();
    assertEquals(store, d.runStore());
    assertEquals(journal, d.toolCallJournal());
    assertEquals(UnsafeResumePolicy.AUTO_FAIL_AND_CONTINUE, d.unsafeResumePolicy());
    assertEquals(2, d.idempotentToolsOverride().size());
    assertTrue(d.idempotentToolsOverride().get("get_x"));
    assertFalse(d.idempotentToolsOverride().get("send"));
  }

  @Test
  void builderRebuildPreservesAll() {
    var original =
        Durability.newBuilder()
            .withRunStore(new InMemoryRunStore())
            .withToolCallJournal(new InMemoryToolCallJournal())
            .withUnsafeResumePolicy(UnsafeResumePolicy.AUTO_FAIL_AND_CONTINUE)
            .withIdempotentToolOverride("a", true)
            .build();
    var copy = Durability.newBuilder(original).build();
    assertEquals(original, copy);
  }

  @Test
  void builderRequiresRunStore() {
    var b = Durability.newBuilder().withToolCallJournal(new InMemoryToolCallJournal());
    assertThrows(IllegalStateException.class, b::build);
  }

  @Test
  void builderRequiresJournal() {
    var b = Durability.newBuilder().withRunStore(new InMemoryRunStore());
    assertThrows(IllegalStateException.class, b::build);
  }

  @Test
  void builderRejectsNullPolicy() {
    var b = Durability.newBuilder();
    assertThrows(NullPointerException.class, () -> b.withUnsafeResumePolicy(null));
  }

  @Test
  void builderRejectsBlankToolName() {
    var b = Durability.newBuilder();
    assertThrows(IllegalArgumentException.class, () -> b.withIdempotentToolOverride(null, true));
    assertThrows(IllegalArgumentException.class, () -> b.withIdempotentToolOverride("", true));
    assertThrows(IllegalArgumentException.class, () -> b.withIdempotentToolOverride("  ", true));
  }

  @Test
  void builderRejectsNullOverrideMap() {
    var b = Durability.newBuilder();
    assertThrows(NullPointerException.class, () -> b.withIdempotentToolsOverride(null));
  }

  @Test
  void builderRejectsBlankKeyInOverrideMap() {
    var b = Durability.newBuilder();
    var bad = new HashMap<String, Boolean>();
    bad.put("  ", true);
    assertThrows(IllegalArgumentException.class, () -> b.withIdempotentToolsOverride(bad));
  }

  @Test
  void builderRejectsNullKeyInOverrideMap() {
    var b = Durability.newBuilder();
    var bad = new HashMap<String, Boolean>();
    bad.put(null, true);
    assertThrows(IllegalArgumentException.class, () -> b.withIdempotentToolsOverride(bad));
  }

  @Test
  void builderRejectsNullValueInOverrideMap() {
    var b = Durability.newBuilder();
    var bad = new HashMap<String, Boolean>();
    bad.put("send", null);
    assertThrows(IllegalArgumentException.class, () -> b.withIdempotentToolsOverride(bad));
  }

  @Test
  void overrideMapIsImmutable() {
    var d =
        Durability.newBuilder()
            .withRunStore(new InMemoryRunStore())
            .withToolCallJournal(new InMemoryToolCallJournal())
            .withIdempotentToolOverride("a", true)
            .build();
    assertThrows(
        UnsupportedOperationException.class, () -> d.idempotentToolsOverride().put("b", true));
  }

  @Test
  void idempotentOverrideHelper() {
    var d =
        Durability.newBuilder()
            .withRunStore(new InMemoryRunStore())
            .withToolCallJournal(new InMemoryToolCallJournal())
            .withIdempotentToolOverride("known", true)
            .build();
    assertTrue(d.idempotentOverride("known"));
    assertNull(d.idempotentOverride("unknown"));
    assertNull(d.idempotentOverride(null));
  }

  @Test
  void replaceOverridesViaMapSetter() {
    var d =
        Durability.newBuilder()
            .withRunStore(new InMemoryRunStore())
            .withToolCallJournal(new InMemoryToolCallJournal())
            .withIdempotentToolOverride("first", true)
            .withIdempotentToolsOverride(Map.of("second", false))
            .build();
    assertEquals(1, d.idempotentToolsOverride().size());
    assertNotNull(d.idempotentToolsOverride().get("second"));
  }

  @Test
  void recordRejectsNullComponents() {
    assertThrows(
        NullPointerException.class,
        () ->
            new Durability(
                null, new InMemoryToolCallJournal(), UnsafeResumePolicy.FAIL_LOUD, Map.of(), 1));
    assertThrows(
        NullPointerException.class,
        () ->
            new Durability(
                new InMemoryRunStore(), null, UnsafeResumePolicy.FAIL_LOUD, Map.of(), 1));
    assertThrows(
        NullPointerException.class,
        () ->
            new Durability(
                new InMemoryRunStore(), new InMemoryToolCallJournal(), null, Map.of(), 1));
    assertThrows(
        NullPointerException.class,
        () ->
            new Durability(
                new InMemoryRunStore(),
                new InMemoryToolCallJournal(),
                UnsafeResumePolicy.FAIL_LOUD,
                null,
                1));
  }

  @Test
  void recordRejectsNonPositiveCheckpointFrequency() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Durability(
                new InMemoryRunStore(),
                new InMemoryToolCallJournal(),
                UnsafeResumePolicy.FAIL_LOUD,
                Map.of(),
                0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Durability(
                new InMemoryRunStore(),
                new InMemoryToolCallJournal(),
                UnsafeResumePolicy.FAIL_LOUD,
                Map.of(),
                -1));
  }

  @Test
  void defaultCheckpointFrequencyIsOne() {
    assertEquals(1, Durability.inMemory().checkpointFrequency());
  }

  @Test
  void builderRejectsNonPositiveCheckpointFrequency() {
    var builder = Durability.newBuilder();
    assertThrows(IllegalArgumentException.class, () -> builder.withCheckpointFrequency(0));
    assertThrows(IllegalArgumentException.class, () -> builder.withCheckpointFrequency(-5));
  }

  @Test
  void builderSetsCheckpointFrequency() {
    var d =
        Durability.newBuilder()
            .withRunStore(new InMemoryRunStore())
            .withToolCallJournal(new InMemoryToolCallJournal())
            .withCheckpointFrequency(5)
            .build();
    assertEquals(5, d.checkpointFrequency());
  }
}
