/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.repl.sandbox.policy.SandboxPolicy;
import ai.singlr.repl.sandbox.policy.SandboxPolicySerialization;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * End-to-end test that a {@link SandboxPolicy} configured on the host side round-trips through the
 * launch-command argv into {@link JvmSandboxBootstrap#parseSandboxPolicyArg}. Avoids actually
 * launching a subprocess — exercises the encode + buildLaunchCommand + parse path against the
 * argv-builder.
 */
class SandboxPolicyArgvTest {

  @Test
  void permissivePolicyDoesNotAppearInLaunchCommand() {
    var config = JvmSandboxConfig.newBuilder().build();
    var cmd = JvmSandbox.buildLaunchCommand("/fake/java", config);
    for (var arg : cmd) {
      assertFalse(
          arg.startsWith("--sandbox-policy="),
          "Permissive policy should not be propagated; saw: " + arg);
    }
  }

  @Test
  void nonPermissivePolicyTravelsThroughLaunchCommandAndBack() {
    var original =
        SandboxPolicy.newBuilder()
            .withDeniedClasses("java.lang.ProcessBuilder", "java.lang.Runtime")
            .withDeniedPackages("java.net.http")
            .withDenyReflection(true)
            .withDenyNativeAccess(true)
            .withDenyDynamicClassDefinition(true)
            .build();
    var config = JvmSandboxConfig.newBuilder().withSandboxPolicy(original).build();
    var cmd = JvmSandbox.buildLaunchCommand("/fake/java", config, "/tmp/rpc.sock");

    var policyArg =
        cmd.stream()
            .filter(a -> a.startsWith("--sandbox-policy="))
            .findFirst()
            .orElseThrow(() -> new AssertionError("--sandbox-policy not present in launch cmd"));

    var encoded = policyArg.substring("--sandbox-policy=".length());
    var decoded = SandboxPolicySerialization.decode(encoded);
    assertEquals(original.deniedClasses(), decoded.deniedClasses());
    assertEquals(original.deniedPackages(), decoded.deniedPackages());
    assertEquals(original.denyReflection(), decoded.denyReflection());
    assertEquals(original.denyNativeAccess(), decoded.denyNativeAccess());
    assertEquals(original.denyDynamicClassDefinition(), decoded.denyDynamicClassDefinition());

    var parsed =
        JvmSandboxBootstrap.parseSandboxPolicyArg(new String[] {"--rpc-socket=/x", policyArg});
    assertEquals(original.deniedClasses(), parsed.deniedClasses());
    assertEquals(original.deniedPackages(), parsed.deniedPackages());
    assertTrue(parsed.denyReflection());
  }

  @Test
  void parseSandboxPolicyArgReturnsPermissiveWhenAbsent() {
    var parsed = JvmSandboxBootstrap.parseSandboxPolicyArg(new String[] {"--rpc-socket=/x"});
    assertTrue(parsed.isPermissive());
  }

  @Test
  void parseSandboxPolicyArgWithEmptyArgsReturnsPermissive() {
    assertTrue(JvmSandboxBootstrap.parseSandboxPolicyArg(new String[0]).isPermissive());
  }

  @Test
  void encodeOfPolicyWithSingleClassDenyIsDecodable() {
    var original = SandboxPolicy.newBuilder().withDeniedClasses("java.lang.ProcessBuilder").build();
    var encoded = SandboxPolicySerialization.encode(original);
    var decoded = SandboxPolicySerialization.decode(encoded);
    assertEquals(Set.of("java.lang.ProcessBuilder"), decoded.deniedClasses());
  }

  @Test
  void policyArgvFlagAppearsAtEndOfLaunchCommand() {
    var policy = SandboxPolicy.newBuilder().withDenyReflection(true).build();
    var config = JvmSandboxConfig.newBuilder().withSandboxPolicy(policy).build();
    var cmd = JvmSandbox.buildLaunchCommand("/fake/java", config, "/tmp/rpc.sock");
    var last = cmd.get(cmd.size() - 1);
    assertTrue(
        last.startsWith("--sandbox-policy="),
        "Expected --sandbox-policy as last arg, got: " + last);
  }

  @Test
  void rpcSocketArgPrecedesPolicyArgWhenBothPresent() {
    var policy = SandboxPolicy.newBuilder().withDenyReflection(true).build();
    var config = JvmSandboxConfig.newBuilder().withSandboxPolicy(policy).build();
    var cmd = JvmSandbox.buildLaunchCommand("/fake/java", config, "/tmp/rpc.sock");
    var rpcIdx = -1;
    var policyIdx = -1;
    for (var i = 0; i < cmd.size(); i++) {
      if (cmd.get(i).startsWith("--rpc-socket=")) {
        rpcIdx = i;
      } else if (cmd.get(i).startsWith("--sandbox-policy=")) {
        policyIdx = i;
      }
    }
    assertTrue(rpcIdx >= 0 && policyIdx >= 0);
    assertTrue(rpcIdx < policyIdx);
  }

  @Test
  void sameUnencodedPolicyProducesSameEncoding() {
    var p =
        SandboxPolicy.newBuilder().withDeniedClasses("a.B", "c.D").withDenyReflection(true).build();
    var a = SandboxPolicySerialization.encode(p);
    var b = SandboxPolicySerialization.encode(p);
    assertEquals(a, b);
    assertSame(p.onViolation(), SandboxPolicySerialization.decode(a).onViolation());
  }
}
