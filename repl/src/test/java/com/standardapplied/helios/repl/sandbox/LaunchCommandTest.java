/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.standardapplied.helios.repl.sandbox.policy.SandboxPolicy;
import com.standardapplied.helios.repl.sandbox.policy.SandboxPolicySerialization;
import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The exact command line the host builds for the sandbox subprocess, for a host JVM launched from
 * the class path and from the module path, under each {@link SubprocessModules} setting, with and
 * without a policy that has to travel to the subprocess.
 */
class LaunchCommandTest {

  private static final String JAVA = "/fake/java";

  private static final String RPC_SOCKET = "/tmp/helios-rpc-1/rpc.sock";

  private static final Path HOST_CWD = Path.of("/host/app");

  private static final String RAW_CLASSPATH =
      "lib/helios-repl.jar" + File.pathSeparator + "/opt/libs/jackson.jar";

  private static final String RESOLVED_CLASSPATH =
      "/host/app/lib/helios-repl.jar" + File.pathSeparator + "/opt/libs/jackson.jar";

  private static final List<String> FILTERED_PARENT_ARGS =
      List.of(
          "-Xmx2g",
          "-Xms64m",
          "-Dauth.token=secret",
          "-javaagent:/opt/agent.jar",
          "-agentlib:jdwp=transport=dt_socket",
          "-agentpath:/opt/libagent.so",
          "--add-modules=ALL-MODULE-PATH",
          "--limit-modules=java.base",
          "--enable-native-access=ALL-UNNAMED");

  private static final List<String> KEPT_PARENT_ARGS =
      List.of("--add-opens=java.base/java.lang=ALL-UNNAMED", "-XX:+UseZGC", "--enable-preview");

  private static final List<String> MODULE_PATH = List.of("--module-path", "/host/app/mods");

  private static final String MAIN_CLASS =
      "com.standardapplied.helios.repl.sandbox.JvmSandboxBootstrap";

  private static final String ROOTS = "java.base,java.compiler,jdk.compiler,jdk.jshell";

  static Stream<Arguments> launches() {
    var rows = new ArrayList<Arguments>();
    for (var modulePath : List.of(false, true)) {
      for (var modules : List.of("unrestricted", "minimal", "extras")) {
        for (var policy : List.of("permissive", "noEgress")) {
          rows.add(Arguments.of(modulePath, modules, policy));
        }
      }
    }
    return rows.stream();
  }

  @ParameterizedTest(name = "module path {0}, modules {1}, policy {2}")
  @MethodSource("launches")
  void commandLineIsExact(boolean modulePath, String modules, String policy) {
    var config =
        JvmSandboxConfig.newBuilder()
            .withSubprocessModules(modules(modules))
            .withSandboxPolicy(policy(policy))
            .build();

    var command =
        SandboxLauncher.buildLaunchCommand(
            JAVA, config, RPC_SOCKET, parentArgs(modulePath), RAW_CLASSPATH, HOST_CWD);

    var expected = new ArrayList<String>();
    expected.add(JAVA);
    expected.add("-Xmx256m");
    if (modulePath) {
      expected.addAll(MODULE_PATH);
    }
    expected.addAll(KEPT_PARENT_ARGS);
    expected.addAll(List.of("-cp", RESOLVED_CLASSPATH));
    if (modulePath) {
      expected.addAll(List.of("--add-modules", "com.standardapplied.helios.repl"));
    }
    expected.addAll(limitModules(modulePath, modules));
    expected.addAll(List.of(MAIN_CLASS, "--rpc-socket=" + RPC_SOCKET, "--stop-grace=PT5S"));
    if (policy.equals("noEgress")) {
      expected.add(
          "--sandbox-policy=" + SandboxPolicySerialization.encode(SandboxPolicy.noEgress()));
    }
    assertEquals(expected, command);
  }

  @Test
  void commandLineWithoutASocketEndsWithTheStopGrace() {
    var config =
        JvmSandboxConfig.newBuilder()
            .withMaxHeapMb(1024)
            .withStopGrace(Duration.ofMillis(1500))
            .build();

    var command = SandboxLauncher.buildLaunchCommand(JAVA, config, null, List.of(), "", HOST_CWD);

    assertEquals(List.of(JAVA, "-Xmx1024m", MAIN_CLASS, "--stop-grace=PT1.5S"), command);
  }

  private static List<String> parentArgs(boolean modulePath) {
    var args = new ArrayList<String>();
    if (modulePath) {
      args.addAll(MODULE_PATH);
    }
    args.addAll(interleave(FILTERED_PARENT_ARGS, KEPT_PARENT_ARGS));
    return args;
  }

  private static List<String> interleave(List<String> dropped, List<String> kept) {
    var args = new ArrayList<String>();
    for (var i = 0; i < Math.max(dropped.size(), kept.size()); i++) {
      if (i < dropped.size()) {
        args.add(dropped.get(i));
      }
      if (i < kept.size()) {
        args.add(kept.get(i));
      }
    }
    return args;
  }

  private static List<String> limitModules(boolean modulePath, String modules) {
    var bootstrap = modulePath ? ",com.standardapplied.helios.repl" : "";
    return switch (modules) {
      case "minimal" -> List.of("--limit-modules", ROOTS + bootstrap);
      case "extras" -> List.of("--limit-modules", ROOTS + bootstrap + ",java.sql");
      default -> List.of();
    };
  }

  private static SubprocessModules modules(String name) {
    return switch (name) {
      case "minimal" -> SubprocessModules.minimal();
      case "extras" -> SubprocessModules.allowingExtras("java.sql");
      default -> SubprocessModules.unrestricted();
    };
  }

  private static SandboxPolicy policy(String name) {
    return name.equals("noEgress") ? SandboxPolicy.noEgress() : SandboxPolicy.permissive();
  }
}
