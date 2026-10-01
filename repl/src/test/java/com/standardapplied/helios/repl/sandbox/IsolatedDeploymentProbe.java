/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox;

import ai.singlr.repl.host.HostFunctionRegistry;
import java.time.Duration;

/**
 * Entry point {@link IsolatedDeploymentTest} runs inside the least-privilege container. Boots a
 * real {@link JvmSandbox} with the permissive policy — so any denial observed comes from the OS
 * boundary, not the bytecode verifier — then reports whether a snippet can read the host sentinel
 * file and connect to the host listener. Each probe prints one {@code LABEL:REACHED} or {@code
 * LABEL:DENIED <exception>} line on stdout.
 */
public final class IsolatedDeploymentProbe {

  private IsolatedDeploymentProbe() {}

  public static void main(String[] args) {
    var sentinel = args[0];
    var port = args[1];
    var config =
        JvmSandboxConfig.newBuilder()
            .withExecutionTimeout(Duration.ofSeconds(30))
            .withSubprocessStartupTimeout(Duration.ofSeconds(60))
            .build();
    try (var sandbox = JvmSandbox.create(config, new HostFunctionRegistry())) {
      report(
          sandbox,
          "FS",
          "java.nio.file.Files.readString(java.nio.file.Path.of(\"" + sentinel + "\"))");
      report(sandbox, "NET", "new java.net.Socket(\"127.0.0.1\", " + port + ").toString()");
    }
  }

  private static void report(Sandbox sandbox, String label, String expression) {
    var code =
        "try { System.out.println(\""
            + label
            + ":REACHED \" + ("
            + expression
            + ")); } catch (Exception e) { System.out.println(\""
            + label
            + ":DENIED \" + e.getClass().getName()); }";
    var result = sandbox.execute(ExecutionRequest.java(code));
    System.out.print(result.stdout());
    System.err.print(result.stderr());
  }
}
