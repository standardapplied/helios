/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.repl.host.HostFunctionRegistry;
import ai.singlr.repl.sandbox.policy.SandboxPolicy;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * End-to-end enforcement test through the real {@link JvmSandbox} subprocess. Closes the gap left
 * by the in-process {@code SandboxPolicyEnforcementTest}: this one proves the policy travels
 * host→subprocess via argv, the bootstrap reconstructs it, the verifier wires up against the real
 * JShell instance in the subprocess, and the stderr signal makes the round trip back into {@link
 * ExecutionResult#stderr()}.
 *
 * <p>Each test launches a fresh JVM subprocess (~1-2 s each), so this class deliberately holds
 * exactly two tests — one denial, one allow — covering the wire end-to-end without ballooning CI
 * cost.
 */
class SandboxPolicySubprocessEnforcementTest {

  @Test
  void denyingPolicyRejectsDeniedSnippetInRealSubprocess() {
    var policy = SandboxPolicy.newBuilder().withDenyReflection(true).build();
    var config =
        JvmSandboxConfig.newBuilder()
            .withSandboxPolicy(policy)
            .withExecutionTimeout(Duration.ofSeconds(20))
            .build();
    var registry = new HostFunctionRegistry();
    try (var sandbox = JvmSandbox.create(config, registry)) {
      var result =
          sandbox.execute(ExecutionRequest.java("var c = Class.forName(\"java.lang.String\");"));
      assertTrue(
          result.stderr().contains("Sandbox policy denied")
              || result.stderr().contains("denyReflection"),
          () ->
              "Expected policy message in subprocess stderr, got: stderr=["
                  + result.stderr()
                  + "] stdout=["
                  + result.stdout()
                  + "]");
    }
  }

  @Test
  void denyingPolicyAllowsLegitimateSnippetInRealSubprocess() {
    var policy = SandboxPolicy.newBuilder().withDenyReflection(true).build();
    var config =
        JvmSandboxConfig.newBuilder()
            .withSandboxPolicy(policy)
            .withExecutionTimeout(Duration.ofSeconds(20))
            .build();
    var registry = new HostFunctionRegistry();
    try (var sandbox = JvmSandbox.create(config, registry)) {
      var result = sandbox.execute(ExecutionRequest.java("int sum = 21 * 2;"));
      assertEquals(
          0,
          result.exitCode(),
          () ->
              "Allowed snippet should have exit code 0; stderr=["
                  + result.stderr()
                  + "] stdout=["
                  + result.stdout()
                  + "]");
      assertTrue(
          !result.stderr().contains("Sandbox policy denied"),
          () -> "Allowed snippet unexpectedly produced a policy denial: " + result.stderr());
    }
  }
}
