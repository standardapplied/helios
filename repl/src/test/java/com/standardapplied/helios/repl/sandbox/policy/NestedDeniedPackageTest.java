/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.repl.sandbox.policy;

import static java.lang.constant.ConstantDescs.CD_void;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.test.ChildJvm;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The violation message for a reference inside nested denied packages names the most specific of
 * them in every JVM, whatever order the policy's set iterates in.
 */
class NestedDeniedPackageTest {

  /** Prints the violation message for {@code java.lang.reflect.Method.invoke}. */
  static final class Probe {
    public static void main(String[] args) {
      System.out.print(violation());
    }
  }

  @Test
  void violationNamesTheMostSpecificDeniedPackageInEveryJvm(@TempDir Path dir) {
    assertEquals(
        new SandboxPolicyException(
                "java/lang/reflect/Method", "invoke", "deniedPackages:java.lang.reflect")
            .getMessage(),
        ChildJvm.sameInEveryJvm(dir, Probe.class));
  }

  private static String violation() {
    var policy =
        SandboxPolicy.newBuilder().withDeniedPackages("java.lang", "java.lang.reflect").build();
    var bytes =
        PolicyBytecodeVerifierTest.buildTestClass(
            code ->
                code.invokestatic(
                    ClassDesc.of("java.lang.reflect.Method"),
                    "invoke",
                    MethodTypeDesc.of(CD_void)));
    return assertThrows(
            SandboxPolicyException.class,
            () -> new PolicyBytecodeVerifier(policy).verify("TestClass", bytes))
        .getMessage();
  }
}
