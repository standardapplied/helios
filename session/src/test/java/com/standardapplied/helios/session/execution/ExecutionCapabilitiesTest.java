/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ExecutionCapabilitiesTest {

  @Test
  void canonicalConstructorAcceptsValidValues() {
    var c =
        new ExecutionCapabilities(
            EnumSet.of(Runtime.BASH, Runtime.PYTHON), true, false, Duration.ofMinutes(5));
    assertEquals(Set.of(Runtime.BASH, Runtime.PYTHON), c.supportedRuntimes());
    assertTrue(c.networkAllowed());
    assertFalse(c.filesystemWriteAllowed());
    assertEquals(Duration.ofMinutes(5), c.maxTimeout());
  }

  @Test
  void canonicalConstructorAllowsEmptyRuntimeSet() {
    var c = new ExecutionCapabilities(Set.of(), false, false, Duration.ofSeconds(1));
    assertTrue(c.supportedRuntimes().isEmpty());
  }

  @Test
  void supportedRuntimesNullRejected() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new ExecutionCapabilities(null, false, false, Duration.ofSeconds(1)));
    assertEquals("supportedRuntimes must not be null", ex.getMessage());
  }

  @Test
  void maxTimeoutNullRejected() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new ExecutionCapabilities(Set.of(), false, false, null));
    assertEquals("maxTimeout must not be null", ex.getMessage());
  }

  @Test
  void maxTimeoutZeroRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new ExecutionCapabilities(Set.of(), false, false, Duration.ZERO));
    assertTrue(ex.getMessage().startsWith("maxTimeout must be strictly positive"));
  }

  @Test
  void maxTimeoutNegativeRejected() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new ExecutionCapabilities(Set.of(), false, false, Duration.ofMillis(-1)));
    assertTrue(ex.getMessage().startsWith("maxTimeout must be strictly positive"));
  }

  @Test
  void supportedRuntimesAreDefensivelyCopied() {
    var mutable = new HashSet<Runtime>();
    mutable.add(Runtime.BASH);
    var c = new ExecutionCapabilities(mutable, false, false, Duration.ofSeconds(1));
    mutable.add(Runtime.PYTHON);
    assertEquals(Set.of(Runtime.BASH), c.supportedRuntimes());
  }

  @Test
  void supportsTrueForRegisteredRuntime() {
    var c = new ExecutionCapabilities(Set.of(Runtime.PYTHON), false, false, Duration.ofSeconds(1));
    assertTrue(c.supports(Runtime.PYTHON));
  }

  @Test
  void supportsFalseForUnregisteredRuntime() {
    var c = new ExecutionCapabilities(Set.of(Runtime.PYTHON), false, false, Duration.ofSeconds(1));
    assertFalse(c.supports(Runtime.BASH));
  }

  @Test
  void supportsNullRejected() {
    var c = new ExecutionCapabilities(Set.of(), false, false, Duration.ofSeconds(1));
    var ex = assertThrows(NullPointerException.class, () -> c.supports(null));
    assertEquals("runtime must not be null", ex.getMessage());
  }

  // ── Builder ───────────────────────────────────────────────────────────────

  @Test
  void builderProducesDefaults() {
    var c = ExecutionCapabilities.newBuilder().build();
    assertTrue(c.supportedRuntimes().isEmpty());
    assertFalse(c.networkAllowed());
    assertFalse(c.filesystemWriteAllowed());
    assertEquals(Duration.ofMinutes(2), c.maxTimeout());
  }

  @Test
  void builderWithAllOptions() {
    var c =
        ExecutionCapabilities.newBuilder()
            .withSupportedRuntimes(Set.of(Runtime.BASH))
            .withSupportedRuntime(Runtime.PYTHON)
            .withNetworkAllowed(true)
            .withFilesystemWriteAllowed(true)
            .withMaxTimeout(Duration.ofMinutes(10))
            .build();
    assertEquals(Set.of(Runtime.BASH, Runtime.PYTHON), c.supportedRuntimes());
    assertTrue(c.networkAllowed());
    assertTrue(c.filesystemWriteAllowed());
    assertEquals(Duration.ofMinutes(10), c.maxTimeout());
  }

  @Test
  void builderWithSupportedRuntimesNullRejected() {
    var b = ExecutionCapabilities.newBuilder();
    var ex = assertThrows(NullPointerException.class, () -> b.withSupportedRuntimes(null));
    assertEquals("runtimes must not be null", ex.getMessage());
  }

  @Test
  void builderWithSupportedRuntimesNullElementRejected() {
    var b = ExecutionCapabilities.newBuilder();
    var withNull = new HashSet<Runtime>();
    withNull.add(null);
    var ex = assertThrows(NullPointerException.class, () -> b.withSupportedRuntimes(withNull));
    assertEquals("runtimes must not contain null", ex.getMessage());
  }

  @Test
  void builderWithSupportedRuntimeNullRejected() {
    var b = ExecutionCapabilities.newBuilder();
    var ex = assertThrows(NullPointerException.class, () -> b.withSupportedRuntime(null));
    assertEquals("runtime must not be null", ex.getMessage());
  }

  @Test
  void builderWithMaxTimeoutNullRejected() {
    var b = ExecutionCapabilities.newBuilder();
    var ex = assertThrows(NullPointerException.class, () -> b.withMaxTimeout(null));
    assertEquals("maxTimeout must not be null", ex.getMessage());
  }

  @Test
  void builderWithMaxTimeoutZeroRejected() {
    var b = ExecutionCapabilities.newBuilder();
    var ex = assertThrows(IllegalArgumentException.class, () -> b.withMaxTimeout(Duration.ZERO));
    assertTrue(ex.getMessage().startsWith("maxTimeout must be strictly positive"));
  }

  @Test
  void builderWithMaxTimeoutNegativeRejected() {
    var b = ExecutionCapabilities.newBuilder();
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> b.withMaxTimeout(Duration.ofSeconds(-1)));
    assertTrue(ex.getMessage().startsWith("maxTimeout must be strictly positive"));
  }
}
