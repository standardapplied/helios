/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PgPromptRegistryTest {

  private PgPromptRegistry registry;

  @BeforeEach
  void setUp() {
    PgTestSupport.truncate();
    registry = new PgPromptRegistry(PgTestSupport.pgConfig());
  }

  @Test
  void registerFirstVersion() {
    var prompt = registry.register("greeting", "Hello {name}!");

    assertNotNull(prompt.id());
    assertEquals("greeting", prompt.name());
    assertEquals("Hello {name}!", prompt.content());
    assertEquals(1, prompt.version());
    assertTrue(prompt.active());
    assertEquals(Set.of("name"), prompt.variables());
    assertNotNull(prompt.createdAt());
  }

  @Test
  void registerSecondVersionDeactivatesPrevious() {
    var v1 = registry.register("greeting", "Hello {name}!");
    var v2 = registry.register("greeting", "Hi {name}, welcome to {place}!");

    assertEquals(2, v2.version());
    assertTrue(v2.active());
    assertEquals(Set.of("name", "place"), v2.variables());

    var resolved = registry.resolve("greeting");
    assertEquals(v2.id(), resolved.id());

    var v1Now = registry.resolve("greeting", 1);
    assertFalse(v1Now.active());
    assertEquals(v1.id(), v1Now.id());
  }

  @Test
  void registerMultipleVersions() {
    registry.register("prompt", "v1");
    registry.register("prompt", "v2");
    var v3 = registry.register("prompt", "v3");

    assertEquals(3, v3.version());
    assertTrue(v3.active());

    var versions = registry.versions("prompt");
    assertEquals(3, versions.size());
    assertFalse(versions.get(0).active());
    assertFalse(versions.get(1).active());
    assertTrue(versions.get(2).active());
  }

  @Test
  void registerDifferentNames() {
    var greeting = registry.register("greeting", "Hello!");
    var farewell = registry.register("farewell", "Goodbye!");

    assertEquals(1, greeting.version());
    assertEquals(1, farewell.version());

    assertEquals("Hello!", registry.resolve("greeting").content());
    assertEquals("Goodbye!", registry.resolve("farewell").content());
  }

  @Test
  void registerBlankNameThrows() {
    assertThrows(IllegalArgumentException.class, () -> registry.register("", "content"));
    assertThrows(IllegalArgumentException.class, () -> registry.register("   ", "content"));
    assertThrows(IllegalArgumentException.class, () -> registry.register(null, "content"));
  }

  @Test
  void registerNullContentThrows() {
    assertThrows(IllegalArgumentException.class, () -> registry.register("name", null));
  }

  @Test
  void resolveByNameReturnsActiveVersion() {
    registry.register("prompt", "version 1");
    registry.register("prompt", "version 2");

    var resolved = registry.resolve("prompt");
    assertEquals("version 2", resolved.content());
    assertTrue(resolved.active());
  }

  @Test
  void resolveByNameReturnsNullForUnknown() {
    assertNull(registry.resolve("nonexistent"));
  }

  @Test
  void resolveByVersionReturnsSpecificVersion() {
    registry.register("prompt", "version 1");
    registry.register("prompt", "version 2");
    registry.register("prompt", "version 3");

    assertEquals("version 1", registry.resolve("prompt", 1).content());
    assertEquals("version 2", registry.resolve("prompt", 2).content());
    assertEquals("version 3", registry.resolve("prompt", 3).content());
  }

  @Test
  void resolveByVersionReturnsNullForInvalidVersion() {
    registry.register("prompt", "v1");

    assertNull(registry.resolve("prompt", 0));
    assertNull(registry.resolve("prompt", 2));
    assertNull(registry.resolve("prompt", -1));
  }

  @Test
  void resolveByVersionReturnsNullForUnknownName() {
    assertNull(registry.resolve("nonexistent", 1));
  }

  @Test
  void versionsReturnsAllInOrder() {
    registry.register("prompt", "first");
    registry.register("prompt", "second");
    registry.register("prompt", "third");

    var versions = registry.versions("prompt");
    assertEquals(3, versions.size());
    assertEquals(1, versions.get(0).version());
    assertEquals(2, versions.get(1).version());
    assertEquals(3, versions.get(2).version());
    assertEquals("first", versions.get(0).content());
    assertEquals("second", versions.get(1).content());
    assertEquals("third", versions.get(2).content());
  }

  @Test
  void versionsReturnsEmptyForUnknown() {
    assertEquals(0, registry.versions("nonexistent").size());
  }

  @Test
  void versionsReturnsImmutableList() {
    registry.register("prompt", "v1");

    assertThrows(UnsupportedOperationException.class, () -> registry.versions("prompt").clear());
  }

  @Test
  void registerEmptyContent() {
    var prompt = registry.register("empty", "");

    assertEquals("", prompt.content());
    assertEquals(Set.of(), prompt.variables());
  }

  @Test
  void variablesRoundTrip() {
    var prompt = registry.register("greet", "Hello {name}, welcome to {place}!");
    assertEquals(Set.of("name", "place"), prompt.variables());

    var resolved = registry.resolve("greet");
    assertEquals(Set.of("name", "place"), resolved.variables());
  }

  @Test
  void registerNameExceedingColumnLengthThrowsPgException() {
    var longName = "x".repeat(256);
    assertThrows(PgException.class, () -> registry.register(longName, "content"));
  }

  @Test
  void registerDraftDoesNotChangeActiveVersion() {
    var v1 = registry.register("prompt", "live");
    var draft = registry.registerDraft("prompt", "candidate {name}");

    assertEquals(2, draft.version());
    assertFalse(draft.active());
    assertEquals(Set.of("name"), draft.variables());
    assertEquals(v1.id(), registry.resolve("prompt").id());
    assertEquals("candidate {name}", registry.resolve("prompt", 2).content());
  }

  @Test
  void registerDraftAsFirstVersionLeavesNoActive() {
    var draft = registry.registerDraft("prompt", "candidate");

    assertEquals(1, draft.version());
    assertFalse(draft.active());
    assertNull(registry.resolve("prompt"));
  }

  @Test
  void activatePromotesDraftAndRollsBack() {
    registry.register("prompt", "v1");
    registry.registerDraft("prompt", "v2");

    var promoted = registry.activate("prompt", 2);
    assertTrue(promoted.active());
    assertEquals("v2", registry.resolve("prompt").content());
    assertFalse(registry.resolve("prompt", 1).active());

    var rolledBack = registry.activate("prompt", 1);
    assertTrue(rolledBack.active());
    assertEquals("v1", registry.resolve("prompt").content());
    assertFalse(registry.resolve("prompt", 2).active());
  }

  @Test
  void activateUnknownNameOrVersionThrows() {
    registry.register("prompt", "v1");

    assertThrows(IllegalArgumentException.class, () -> registry.activate("nonexistent", 1));
    assertThrows(IllegalArgumentException.class, () -> registry.activate("prompt", 99));
  }

  @Test
  void registerDraftValidatesLikeRegister() {
    assertThrows(IllegalArgumentException.class, () -> registry.registerDraft("", "content"));
    assertThrows(IllegalArgumentException.class, () -> registry.registerDraft("name", null));
  }

  @Test
  void concurrentRegistration() throws Exception {
    var threadCount = 8;
    var promptsPerThread = 50;
    var executor = Executors.newFixedThreadPool(threadCount);
    var latch = new CountDownLatch(1);

    var futures = new ArrayList<java.util.concurrent.Future<?>>();
    for (int t = 0; t < threadCount; t++) {
      var threadName = "prompt-" + t;
      futures.add(
          executor.submit(
              () -> {
                try {
                  latch.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                for (int i = 0; i < promptsPerThread; i++) {
                  registry.register(threadName, "content " + i);
                }
              }));
    }

    latch.countDown();

    for (var future : futures) {
      future.get(30, TimeUnit.SECONDS);
    }

    for (int t = 0; t < threadCount; t++) {
      var versions = registry.versions("prompt-" + t);
      assertEquals(promptsPerThread, versions.size());

      var ids = new HashSet<>();
      for (var v : versions) {
        assertTrue(ids.add(v.id()));
        assertEquals(v.version(), versions.indexOf(v) + 1);
      }

      var active = registry.resolve("prompt-" + t);
      assertNotNull(active);
      assertTrue(active.active());
      assertEquals(promptsPerThread, active.version());
    }

    executor.shutdown();
  }
}
