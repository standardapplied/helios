/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.repl.host.HostFunctionRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * L3 module-restriction tests. Two layers:
 *
 * <ol>
 *   <li>Unit-level argv tests: {@code buildLaunchCommand} emits {@code --limit-modules} iff the
 *       configured {@link SubprocessModules} is not {@link SubprocessModules.Unrestricted}.
 *   <li>Subprocess-level tests: launching a real {@link JvmSandbox} with {@link
 *       SubprocessModules#minimal()} actually strips {@code java.net.http} so the snippet's import
 *       fails to compile inside the subprocess JShell.
 * </ol>
 */
class SubprocessModulesLaunchTest {

  @Test
  void launchCommandOmitsLimitModulesWhenUnrestricted() {
    var config = JvmSandboxConfig.newBuilder().build();
    var cmd = JvmSandbox.buildLaunchCommand("/fake/java", config);
    assertFalse(
        cmd.contains("--limit-modules"),
        () -> "Unrestricted should not emit --limit-modules; cmd was: " + cmd);
  }

  @Test
  void launchCommandIncludesLimitModulesWhenMinimal() {
    var config =
        JvmSandboxConfig.newBuilder().withSubprocessModules(SubprocessModules.minimal()).build();
    var cmd = JvmSandbox.buildLaunchCommand("/fake/java", config);
    var idx = cmd.indexOf("--limit-modules");
    assertTrue(idx >= 0, () -> "--limit-modules missing from cmd: " + cmd);
    var arg = cmd.get(idx + 1);
    for (var required : SubprocessModules.REQUIRED_ROOTS) {
      assertTrue(arg.contains(required), () -> "missing " + required + " in arg: " + arg);
    }
  }

  @Test
  void launchCommandIncludesExtrasWhenAllowing() {
    var config =
        JvmSandboxConfig.newBuilder()
            .withSubprocessModules(SubprocessModules.allowingExtras("java.net.http"))
            .build();
    var cmd = JvmSandbox.buildLaunchCommand("/fake/java", config);
    var idx = cmd.indexOf("--limit-modules");
    assertTrue(idx >= 0);
    assertTrue(cmd.get(idx + 1).contains("java.net.http"));
  }

  @Test
  void minimalModulesStripsJavaSqlFromBootLayer() {
    var config =
        JvmSandboxConfig.newBuilder()
            .withSubprocessModules(SubprocessModules.minimal())
            .withExecutionTimeout(Duration.ofSeconds(20))
            .build();
    var registry = new HostFunctionRegistry();
    try (var sandbox = JvmSandbox.create(config, registry)) {
      var result =
          sandbox.execute(
              ExecutionRequest.java(
                  "boolean sqlPresent ="
                      + " ModuleLayer.boot().findModule(\"java.sql\").isPresent();\n"
                      + "System.out.println(sqlPresent);"));
      assertTrue(
          result.stdout().contains("false"),
          () ->
              "ModuleLayer.boot() should not contain java.sql under minimal modules; got stdout=["
                  + result.stdout()
                  + "] stderr=["
                  + result.stderr()
                  + "]");
    }
  }

  @Test
  void minimalModulesStripsJavaSqlFromSnippetCompile() {
    var config =
        JvmSandboxConfig.newBuilder()
            .withSubprocessModules(SubprocessModules.minimal())
            .withExecutionTimeout(Duration.ofSeconds(20))
            .build();
    var registry = new HostFunctionRegistry();
    try (var sandbox = JvmSandbox.create(config, registry)) {
      var result =
          sandbox.execute(ExecutionRequest.java("var d = java.sql.DriverManager.getDrivers();"));
      var combined = result.stdout() + "\n" + result.stderr();
      assertTrue(
          combined.contains("java.sql")
              || combined.contains("cannot find symbol")
              || combined.contains("does not exist")
              || combined.contains("not visible"),
          () ->
              "Expected module-not-observable diagnostic for java.sql; got stdout=["
                  + result.stdout()
                  + "] stderr=["
                  + result.stderr()
                  + "] exitCode="
                  + result.exitCode());
    }
  }

  @Test
  void minimalModulesStillRunsBasicSnippet() {
    var config =
        JvmSandboxConfig.newBuilder()
            .withSubprocessModules(SubprocessModules.minimal())
            .withExecutionTimeout(Duration.ofSeconds(20))
            .build();
    var registry = new HostFunctionRegistry();
    try (var sandbox = JvmSandbox.create(config, registry)) {
      var result = sandbox.execute(ExecutionRequest.java("int x = 21 * 2;"));
      assertEquals(
          0,
          result.exitCode(),
          () -> "Basic snippet should run under minimal modules; stderr=[" + result.stderr() + "]");
    }
  }

  @Test
  void allowingExtrasMakesNamedModuleObservable() {
    var config =
        JvmSandboxConfig.newBuilder()
            .withSubprocessModules(SubprocessModules.allowingExtras("java.sql"))
            .withExecutionTimeout(Duration.ofSeconds(20))
            .build();
    var registry = new HostFunctionRegistry();
    try (var sandbox = JvmSandbox.create(config, registry)) {
      var result =
          sandbox.execute(
              ExecutionRequest.java(
                  "boolean sqlPresent = ModuleLayer.boot().findModule(\"java.sql\").isPresent();\n"
                      + "System.out.println(sqlPresent);"));
      assertTrue(
          result.stdout().contains("true"),
          () ->
              "java.sql should be observable when added via allowingExtras; stdout=["
                  + result.stdout()
                  + "] stderr=["
                  + result.stderr()
                  + "]");
    }
  }
}
