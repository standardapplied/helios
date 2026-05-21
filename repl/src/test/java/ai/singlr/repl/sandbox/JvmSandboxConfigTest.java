/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.repl.sandbox.policy.SandboxPolicy;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class JvmSandboxConfigTest {

  private static final SandboxPolicy PERMISSIVE = SandboxPolicy.permissive();

  @Test
  void defaultsFactory() {
    var config = JvmSandboxConfig.defaults();
    assertEquals(Duration.ofSeconds(30), config.executionTimeout());
    assertEquals(256, config.maxHeapMb());
    assertEquals(Duration.ofSeconds(60), config.callTimeout());
    assertNotNull(config.sandboxPolicy());
    assertTrue(config.sandboxPolicy().isPermissive());
  }

  @Test
  void constructorSetsFields() {
    var policy = SandboxPolicy.newBuilder().withDenyReflection(true).build();
    var config =
        new JvmSandboxConfig(
            Duration.ofSeconds(10), 512, Duration.ofSeconds(30), Duration.ofSeconds(20), policy);
    assertEquals(Duration.ofSeconds(10), config.executionTimeout());
    assertEquals(512, config.maxHeapMb());
    assertEquals(Duration.ofSeconds(30), config.callTimeout());
    assertEquals(Duration.ofSeconds(20), config.subprocessStartupTimeout());
    assertSame(policy, config.sandboxPolicy());
  }

  @Test
  void nullExecutionTimeoutThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new JvmSandboxConfig(
                null, 256, Duration.ofSeconds(60), Duration.ofSeconds(10), PERMISSIVE));
  }

  @Test
  void zeroExecutionTimeoutThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new JvmSandboxConfig(
                Duration.ZERO, 256, Duration.ofSeconds(60), Duration.ofSeconds(10), PERMISSIVE));
  }

  @Test
  void negativeExecutionTimeoutThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new JvmSandboxConfig(
                Duration.ofSeconds(-1),
                256,
                Duration.ofSeconds(60),
                Duration.ofSeconds(10),
                PERMISSIVE));
  }

  @Test
  void zeroHeapThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new JvmSandboxConfig(
                Duration.ofSeconds(30),
                0,
                Duration.ofSeconds(60),
                Duration.ofSeconds(10),
                PERMISSIVE));
  }

  @Test
  void negativeHeapThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new JvmSandboxConfig(
                Duration.ofSeconds(30),
                -1,
                Duration.ofSeconds(60),
                Duration.ofSeconds(10),
                PERMISSIVE));
  }

  @Test
  void nullCallTimeoutThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new JvmSandboxConfig(
                Duration.ofSeconds(30), 256, null, Duration.ofSeconds(10), PERMISSIVE));
  }

  @Test
  void zeroCallTimeoutThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new JvmSandboxConfig(
                Duration.ofSeconds(30), 256, Duration.ZERO, Duration.ofSeconds(10), PERMISSIVE));
  }

  @Test
  void negativeCallTimeoutThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new JvmSandboxConfig(
                Duration.ofSeconds(30),
                256,
                Duration.ofSeconds(-1),
                Duration.ofSeconds(10),
                PERMISSIVE));
  }

  @Test
  void builderDefaults() {
    var config = JvmSandboxConfig.newBuilder().build();
    assertEquals(JvmSandboxConfig.DEFAULT_EXECUTION_TIMEOUT, config.executionTimeout());
    assertEquals(JvmSandboxConfig.DEFAULT_MAX_HEAP_MB, config.maxHeapMb());
    assertEquals(JvmSandboxConfig.DEFAULT_CALL_TIMEOUT, config.callTimeout());
    assertTrue(config.sandboxPolicy().isPermissive());
  }

  @Test
  void builderAllOptions() {
    var policy = SandboxPolicy.newBuilder().withDenyNativeAccess(true).build();
    var config =
        JvmSandboxConfig.newBuilder()
            .withExecutionTimeout(Duration.ofMinutes(2))
            .withMaxHeapMb(1024)
            .withCallTimeout(Duration.ofMinutes(5))
            .withSandboxPolicy(policy)
            .build();
    assertEquals(Duration.ofMinutes(2), config.executionTimeout());
    assertEquals(1024, config.maxHeapMb());
    assertEquals(Duration.ofMinutes(5), config.callTimeout());
    assertSame(policy, config.sandboxPolicy());
  }

  @Test
  void defaultConstants() {
    assertEquals(Duration.ofSeconds(30), JvmSandboxConfig.DEFAULT_EXECUTION_TIMEOUT);
    assertEquals(256, JvmSandboxConfig.DEFAULT_MAX_HEAP_MB);
    assertEquals(Duration.ofSeconds(60), JvmSandboxConfig.DEFAULT_CALL_TIMEOUT);
    assertEquals(Duration.ofSeconds(10), JvmSandboxConfig.DEFAULT_SUBPROCESS_STARTUP_TIMEOUT);
  }

  @Test
  void builderRoundtripsSubprocessStartupTimeout() {
    var config =
        JvmSandboxConfig.newBuilder().withSubprocessStartupTimeout(Duration.ofMillis(250)).build();
    assertEquals(Duration.ofMillis(250), config.subprocessStartupTimeout());
  }

  @Test
  void builderDefaultsSubprocessStartupTimeoutToTenSeconds() {
    var config = JvmSandboxConfig.newBuilder().build();
    assertEquals(Duration.ofSeconds(10), config.subprocessStartupTimeout());
  }

  @Test
  void zeroSubprocessStartupTimeoutThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new JvmSandboxConfig(
                Duration.ofSeconds(30), 256, Duration.ofSeconds(60), Duration.ZERO, PERMISSIVE));
  }

  @Test
  void negativeSubprocessStartupTimeoutThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new JvmSandboxConfig(
                Duration.ofSeconds(30),
                256,
                Duration.ofSeconds(60),
                Duration.ofSeconds(-1),
                PERMISSIVE));
  }

  @Test
  void nullSubprocessStartupTimeoutThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new JvmSandboxConfig(
                Duration.ofSeconds(30), 256, Duration.ofSeconds(60), null, PERMISSIVE));
  }

  @Test
  void nullSandboxPolicyThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new JvmSandboxConfig(
                Duration.ofSeconds(30), 256, Duration.ofSeconds(60), Duration.ofSeconds(10), null));
  }

  @Test
  void builderRoundtripsSandboxPolicy() {
    var policy = SandboxPolicy.newBuilder().withDenyDynamicClassDefinition(true).build();
    var config = JvmSandboxConfig.newBuilder().withSandboxPolicy(policy).build();
    assertSame(policy, config.sandboxPolicy());
  }
}
