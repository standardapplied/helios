/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.repl.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class HostFunctionRegistryTest {

  @Test
  void registerAndGet() {
    var registry = new HostFunctionRegistry();
    var fn = new HostFunction("test", "desc", params -> "ok");
    registry.register(fn);

    assertEquals(fn, registry.get("test"));
  }

  @Test
  void getUnknownReturnsNull() {
    var registry = new HostFunctionRegistry();
    assertNull(registry.get("missing"));
  }

  @Test
  void registerNullThrows() {
    var registry = new HostFunctionRegistry();
    assertThrows(IllegalArgumentException.class, () -> registry.register(null));
  }

  @Test
  void registerDuplicateNameThrows() {
    var registry = new HostFunctionRegistry();
    registry.register(new HostFunction("dup", "first", params -> "1"));
    assertThrows(
        IllegalStateException.class,
        () -> registry.register(new HostFunction("dup", "second", params -> "2")));
  }

  @Test
  void registerAfterFreezeThrows() {
    var registry = new HostFunctionRegistry();
    registry.freeze();
    assertThrows(
        IllegalStateException.class,
        () -> registry.register(new HostFunction("late", "too late", params -> "no")));
  }

  @Test
  void freezeState() {
    var registry = new HostFunctionRegistry();
    assertFalse(registry.isFrozen());
    registry.freeze();
    assertTrue(registry.isFrozen());
  }

  @Test
  void allReturnsRegisteredFunctions() {
    var registry = new HostFunctionRegistry();
    registry.register(new HostFunction("a", "desc a", params -> "a"));
    registry.register(new HostFunction("b", "desc b", params -> "b"));

    var all = registry.all();
    assertEquals(2, all.size());
  }

  @Test
  void allReturnsUnmodifiableView() {
    var registry = new HostFunctionRegistry();
    registry.register(new HostFunction("x", "desc", params -> "x"));

    var all = registry.all();
    assertThrows(UnsupportedOperationException.class, () -> all.clear());
  }

  @Test
  void size() {
    var registry = new HostFunctionRegistry();
    assertEquals(0, registry.size());
    registry.register(new HostFunction("a", "desc", params -> "a"));
    assertEquals(1, registry.size());
  }

  @Test
  void getAfterFreezeStillWorks() {
    var registry = new HostFunctionRegistry();
    var fn = new HostFunction("test", "desc", params -> "ok");
    registry.register(fn);
    registry.freeze();

    assertNotNull(registry.get("test"));
  }

  @Test
  void registerRefusesAPreludeHelperNameAndLeavesTheRegistryUnchanged() {
    var registry = new HostFunctionRegistry();
    registry.register(new HostFunction("lookup", "desc", params -> "ok"));

    var thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> registry.register(new HostFunction("print", "desc", params -> "no")));

    assertEquals(
        "Host function name must not be a sandbox prelude helper: print", thrown.getMessage());
    assertEquals(List.of("lookup"), registry.all().stream().map(HostFunction::name).toList());
  }
}
