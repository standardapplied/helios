/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.repl.sandbox.policy.SandboxPolicy;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class JvmSandboxConfigTest {

  private static final SandboxPolicy PERMISSIVE = SandboxPolicy.permissive();
  private static final SubprocessModules UNRESTRICTED = SubprocessModules.unrestricted();

  @Test
  void defaultsFactory() {
    var config = JvmSandboxConfig.defaults();
    assertEquals(Duration.ofSeconds(30), config.executionTimeout());
    assertEquals(Duration.ofSeconds(5), config.stopGrace());
    assertEquals(256, config.maxHeapMb());
    assertEquals(Duration.ofSeconds(60), config.callTimeout());
    assertNotNull(config.sandboxPolicy());
    assertTrue(config.sandboxPolicy().enforcesNothing());
    assertNotNull(config.subprocessModules());
    assertTrue(config.subprocessModules() instanceof SubprocessModules.Unrestricted);
  }

  @Test
  void constructorSetsFields() {
    var policy = SandboxPolicy.newBuilder().withDenyReflection(true).build();
    var modules = SubprocessModules.minimal();
    var config =
        new JvmSandboxConfig(
            Duration.ofSeconds(10),
            Duration.ofMillis(750),
            512,
            Duration.ofSeconds(30),
            Duration.ofSeconds(20),
            policy,
            modules,
            null);
    assertEquals(Duration.ofSeconds(10), config.executionTimeout());
    assertEquals(Duration.ofMillis(750), config.stopGrace());
    assertEquals(512, config.maxHeapMb());
    assertEquals(Duration.ofSeconds(30), config.callTimeout());
    assertEquals(Duration.ofSeconds(20), config.subprocessStartupTimeout());
    assertSame(policy, config.sandboxPolicy());
    assertSame(modules, config.subprocessModules());
  }

  @Test
  void nullExecutionTimeoutThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new JvmSandboxConfig(
                null,
                JvmSandboxConfig.DEFAULT_STOP_GRACE,
                256,
                Duration.ofSeconds(60),
                Duration.ofSeconds(10),
                PERMISSIVE,
                UNRESTRICTED,
                null));
  }

  @Test
  void zeroExecutionTimeoutThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new JvmSandboxConfig(
                Duration.ZERO,
                JvmSandboxConfig.DEFAULT_STOP_GRACE,
                256,
                Duration.ofSeconds(60),
                Duration.ofSeconds(10),
                PERMISSIVE,
                UNRESTRICTED,
                null));
  }

  @Test
  void negativeExecutionTimeoutThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new JvmSandboxConfig(
                Duration.ofSeconds(-1),
                JvmSandboxConfig.DEFAULT_STOP_GRACE,
                256,
                Duration.ofSeconds(60),
                Duration.ofSeconds(10),
                PERMISSIVE,
                UNRESTRICTED,
                null));
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"PT0S", "-PT0.000000001S"})
  void stopGraceThatIsNotPositiveThrows(Duration stopGrace) {
    var builder = JvmSandboxConfig.newBuilder().withStopGrace(stopGrace);

    var thrown = assertThrows(IllegalArgumentException.class, builder::build);

    assertEquals("Stop grace must be positive", thrown.getMessage());
  }

  @Test
  void zeroHeapThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new JvmSandboxConfig(
                Duration.ofSeconds(30),
                JvmSandboxConfig.DEFAULT_STOP_GRACE,
                0,
                Duration.ofSeconds(60),
                Duration.ofSeconds(10),
                PERMISSIVE,
                UNRESTRICTED,
                null));
  }

  @Test
  void negativeHeapThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new JvmSandboxConfig(
                Duration.ofSeconds(30),
                JvmSandboxConfig.DEFAULT_STOP_GRACE,
                -1,
                Duration.ofSeconds(60),
                Duration.ofSeconds(10),
                PERMISSIVE,
                UNRESTRICTED,
                null));
  }

  @Test
  void nullCallTimeoutThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new JvmSandboxConfig(
                Duration.ofSeconds(30),
                JvmSandboxConfig.DEFAULT_STOP_GRACE,
                256,
                null,
                Duration.ofSeconds(10),
                PERMISSIVE,
                UNRESTRICTED,
                null));
  }

  @Test
  void zeroCallTimeoutThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new JvmSandboxConfig(
                Duration.ofSeconds(30),
                JvmSandboxConfig.DEFAULT_STOP_GRACE,
                256,
                Duration.ZERO,
                Duration.ofSeconds(10),
                PERMISSIVE,
                UNRESTRICTED,
                null));
  }

  @Test
  void negativeCallTimeoutThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new JvmSandboxConfig(
                Duration.ofSeconds(30),
                JvmSandboxConfig.DEFAULT_STOP_GRACE,
                256,
                Duration.ofSeconds(-1),
                Duration.ofSeconds(10),
                PERMISSIVE,
                UNRESTRICTED,
                null));
  }

  @Test
  void builderDefaults() {
    var config = JvmSandboxConfig.newBuilder().build();
    assertEquals(JvmSandboxConfig.DEFAULT_EXECUTION_TIMEOUT, config.executionTimeout());
    assertEquals(JvmSandboxConfig.DEFAULT_STOP_GRACE, config.stopGrace());
    assertEquals(JvmSandboxConfig.DEFAULT_MAX_HEAP_MB, config.maxHeapMb());
    assertEquals(JvmSandboxConfig.DEFAULT_CALL_TIMEOUT, config.callTimeout());
    assertTrue(config.sandboxPolicy().enforcesNothing());
    assertTrue(config.subprocessModules() instanceof SubprocessModules.Unrestricted);
    org.junit.jupiter.api.Assertions.assertNull(
        config.workingDirectory(),
        "default working directory must be null — signals ephemeral per-session dir");
  }

  @Test
  void builderRoundtripsExplicitWorkingDirectory() {
    var dir = java.nio.file.Path.of("/var/lib/agent/tenant-42");
    var config = JvmSandboxConfig.newBuilder().withWorkingDirectory(dir).build();
    assertEquals(dir, config.workingDirectory());
  }

  @Test
  void builderAcceptsNullWorkingDirectoryAsEphemeralDefault() {
    var config = JvmSandboxConfig.newBuilder().withWorkingDirectory(null).build();
    org.junit.jupiter.api.Assertions.assertNull(config.workingDirectory());
  }

  @Test
  void builderAllOptions() {
    var policy = SandboxPolicy.newBuilder().withDenyNativeAccess(true).build();
    var modules = SubprocessModules.allowingExtras("java.net.http");
    var config =
        JvmSandboxConfig.newBuilder()
            .withExecutionTimeout(Duration.ofMinutes(2))
            .withStopGrace(Duration.ofMillis(750))
            .withMaxHeapMb(1024)
            .withCallTimeout(Duration.ofMinutes(5))
            .withSandboxPolicy(policy)
            .withSubprocessModules(modules)
            .build();
    assertEquals(Duration.ofMinutes(2), config.executionTimeout());
    assertEquals(Duration.ofMillis(750), config.stopGrace());
    assertEquals(1024, config.maxHeapMb());
    assertEquals(Duration.ofMinutes(5), config.callTimeout());
    assertSame(policy, config.sandboxPolicy());
    assertSame(modules, config.subprocessModules());
  }

  @Test
  void defaultConstants() {
    assertEquals(Duration.ofSeconds(30), JvmSandboxConfig.DEFAULT_EXECUTION_TIMEOUT);
    assertEquals(Duration.ofSeconds(5), JvmSandboxConfig.DEFAULT_STOP_GRACE);
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
                Duration.ofSeconds(30),
                JvmSandboxConfig.DEFAULT_STOP_GRACE,
                256,
                Duration.ofSeconds(60),
                Duration.ZERO,
                PERMISSIVE,
                UNRESTRICTED,
                null));
  }

  @Test
  void negativeSubprocessStartupTimeoutThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new JvmSandboxConfig(
                Duration.ofSeconds(30),
                JvmSandboxConfig.DEFAULT_STOP_GRACE,
                256,
                Duration.ofSeconds(60),
                Duration.ofSeconds(-1),
                PERMISSIVE,
                UNRESTRICTED,
                null));
  }

  @Test
  void nullSubprocessStartupTimeoutThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new JvmSandboxConfig(
                Duration.ofSeconds(30),
                JvmSandboxConfig.DEFAULT_STOP_GRACE,
                256,
                Duration.ofSeconds(60),
                null,
                PERMISSIVE,
                UNRESTRICTED,
                null));
  }

  @Test
  void nullSandboxPolicyThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new JvmSandboxConfig(
                Duration.ofSeconds(30),
                JvmSandboxConfig.DEFAULT_STOP_GRACE,
                256,
                Duration.ofSeconds(60),
                Duration.ofSeconds(10),
                null,
                UNRESTRICTED,
                null));
  }

  @Test
  void nullSubprocessModulesThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new JvmSandboxConfig(
                Duration.ofSeconds(30),
                JvmSandboxConfig.DEFAULT_STOP_GRACE,
                256,
                Duration.ofSeconds(60),
                Duration.ofSeconds(10),
                PERMISSIVE,
                null,
                null));
  }

  @Test
  void builderRoundtripsSandboxPolicy() {
    var policy = SandboxPolicy.newBuilder().withDenyDynamicClassDefinition(true).build();
    var config = JvmSandboxConfig.newBuilder().withSandboxPolicy(policy).build();
    assertSame(policy, config.sandboxPolicy());
  }

  @Test
  void builderRoundtripsSubprocessModules() {
    var modules = SubprocessModules.minimal();
    var config = JvmSandboxConfig.newBuilder().withSubprocessModules(modules).build();
    assertSame(modules, config.subprocessModules());
  }
}
