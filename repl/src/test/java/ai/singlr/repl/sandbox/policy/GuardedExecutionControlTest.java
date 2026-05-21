/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import jdk.jshell.JShell;
import jdk.jshell.Snippet;
import jdk.jshell.spi.ExecutionControl;
import org.junit.jupiter.api.Test;

class GuardedExecutionControlTest {

  @Test
  void verifierIsInvokedForEveryClassOnLoad() throws Exception {
    var observed = new ArrayList<String>();
    BytecodeVerifier verifier = (name, bytes) -> observed.add(name);
    var control = new GuardedExecutionControl(verifier);
    var cbcs =
        new ExecutionControl.ClassBytecodes[] {
          new ExecutionControl.ClassBytecodes("A", new byte[] {1, 2, 3}),
          new ExecutionControl.ClassBytecodes("B", new byte[] {4, 5, 6})
        };
    runVerifyOnly(control, cbcs);
    assertEquals(List.of("A", "B"), observed);
  }

  @Test
  void nullClassBytecodesArrayIsHandled() throws Exception {
    var calls = new AtomicInteger();
    BytecodeVerifier verifier = (name, bytes) -> calls.incrementAndGet();
    var control = new GuardedExecutionControl(verifier);
    runVerifyOnly(control, null);
    assertEquals(0, calls.get());
  }

  @Test
  void verifierExceptionBecomesClassInstallException() {
    BytecodeVerifier verifier =
        (name, bytes) -> {
          throw new SandboxPolicyException("java/lang/ProcessBuilder", "start", "deniedClasses");
        };
    var control = new GuardedExecutionControl(verifier);
    var cbcs =
        new ExecutionControl.ClassBytecodes[] {
          new ExecutionControl.ClassBytecodes("X", new byte[] {0})
        };
    var ex =
        assertThrows(
            ExecutionControl.ClassInstallException.class, () -> runVerifyOnly(control, cbcs));
    assertTrue(ex.getMessage().contains("java.lang.ProcessBuilder"));
  }

  @Test
  void redefineDelegatesToSuperWhenVerifierAllows() {
    BytecodeVerifier verifier = (name, bytes) -> {};
    var control = new GuardedExecutionControl(verifier);
    var cbcs =
        new ExecutionControl.ClassBytecodes[] {
          new ExecutionControl.ClassBytecodes("X", new byte[] {0})
        };
    assertThrows(ExecutionControl.NotImplementedException.class, () -> control.redefine(cbcs));
  }

  @Test
  void redefinePathRoutesThroughVerifier() {
    var calls = new AtomicInteger();
    BytecodeVerifier verifier =
        (name, bytes) -> {
          calls.incrementAndGet();
          throw new SandboxPolicyException(null, null, "rule");
        };
    var control = new GuardedExecutionControl(verifier);
    var cbcs =
        new ExecutionControl.ClassBytecodes[] {
          new ExecutionControl.ClassBytecodes("X", new byte[] {0})
        };
    assertThrows(ExecutionControl.ClassInstallException.class, () -> control.redefine(cbcs));
    assertEquals(1, calls.get());
  }

  @Test
  void verifierAccessorExposesInstance() {
    var verifier = BytecodeVerifier.NO_OP;
    var control = new GuardedExecutionControl(verifier);
    assertSame(verifier, control.verifier());
  }

  @Test
  void constructorRejectsNullVerifier() {
    assertThrows(IllegalArgumentException.class, () -> new GuardedExecutionControl(null));
  }

  @Test
  void permissivePolicyAllowsRealJShellSnippetExecution() throws Exception {
    var provider = new GuardedExecutionControlProvider(SandboxPolicy.permissive());
    try (var shell = JShell.builder().executionEngine(provider, java.util.Map.of()).build()) {
      var events = shell.eval("int x = 21 * 2;");
      assertEquals(1, events.size());
      assertEquals(Snippet.Status.VALID, events.get(0).status());
      assertEquals("42", shell.varValue(shell.variables().iterator().next()));
    }
  }

  @Test
  void providerNameMatchesConstant() {
    var provider = new GuardedExecutionControlProvider(SandboxPolicy.permissive());
    assertEquals("helios-guarded", provider.name());
    assertEquals(GuardedExecutionControlProvider.NAME, provider.name());
  }

  @Test
  void providerDefaultParametersEmpty() {
    var provider = new GuardedExecutionControlProvider(SandboxPolicy.permissive());
    assertTrue(provider.defaultParameters().isEmpty());
  }

  @Test
  void providerPolicyAccessorRoundtrips() {
    var policy = SandboxPolicy.newBuilder().withDenyReflection(true).build();
    var provider = new GuardedExecutionControlProvider(policy);
    assertSame(policy, provider.policy());
  }

  @Test
  void providerConstructorRejectsNullPolicy() {
    assertThrows(IllegalArgumentException.class, () -> new GuardedExecutionControlProvider(null));
  }

  @Test
  void noOpVerifierAcceptsAnyBytes() {
    BytecodeVerifier.NO_OP.verify("anything", new byte[] {-1, -2, -3});
  }

  @Test
  void forPolicyReturnsNoOpInPr1() {
    var v = BytecodeVerifier.forPolicy(SandboxPolicy.permissive());
    v.verify("name", new byte[0]);
  }

  @Test
  void forPolicyRejectsNull() {
    assertThrows(IllegalArgumentException.class, () -> BytecodeVerifier.forPolicy(null));
  }

  /**
   * Invoke {@code load} on the control without triggering JShell's class-installation side effects.
   * The verifier seam fires before {@code super.load(...)}; for unit tests we want to observe that
   * seam alone, so we catch and rethrow only the {@link ExecutionControl.ClassInstallException}
   * (which means the verifier rejected) and swallow any post-verify failure from {@code super}
   * (which means the empty byte arrays are not real class files).
   */
  private static void runVerifyOnly(
      GuardedExecutionControl control, ExecutionControl.ClassBytecodes[] cbcs)
      throws ExecutionControl.ClassInstallException {
    try {
      control.load(cbcs);
    } catch (ExecutionControl.ClassInstallException e) {
      throw e;
    } catch (Throwable ignoredPostVerify) {
      // super.load failed on the synthetic bytes — the verifier seam already ran, which is what
      // this unit test was checking. The end-to-end JShell test covers the post-verify happy path.
    }
  }
}
