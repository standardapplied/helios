/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.repl.sandbox.policy;

import static java.lang.constant.ConstantDescs.CD_void;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.test.ChildJvm;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Path;
import java.util.List;
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
      var policy =
          SandboxPolicy.newBuilder().withDeniedPackages("java.lang", "java.lang.reflect").build();
      System.out.print(violation(policy, "java.lang.reflect.Method").getMessage());
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

  /**
   * These four chains are chosen because every iteration order the JDK can give their set puts at
   * least one shallower package before its nested one, so a rule that takes the first matching
   * package instead of the longest mislabels a reference in every JVM.
   */
  @Test
  void everyReferenceNamesItsMostSpecificPackageAmongSeveralNestedChains() {
    var policy =
        SandboxPolicy.newBuilder()
            .withDeniedPackages(
                "java.lang",
                "java.lang.reflect",
                "java.net",
                "java.net.http",
                "java.nio",
                "java.nio.file",
                "java.time",
                "java.time.format")
            .build();
    var owners =
        List.of(
            "java.lang.reflect.Method",
            "java.net.http.HttpClient",
            "java.nio.file.Files",
            "java.time.format.DateTimeFormatter");

    assertEquals(
        List.of(
            "deniedPackages:java.lang.reflect",
            "deniedPackages:java.net.http",
            "deniedPackages:java.nio.file",
            "deniedPackages:java.time.format"),
        owners.stream().map(owner -> violation(policy, owner).rule()).toList());
  }

  private static SandboxPolicyException violation(SandboxPolicy policy, String owner) {
    var bytes =
        PolicyBytecodeVerifierTest.buildTestClass(
            code -> code.invokestatic(ClassDesc.of(owner), "invoke", MethodTypeDesc.of(CD_void)));
    return assertThrows(
        SandboxPolicyException.class,
        () -> new PolicyBytecodeVerifier(policy).verify("TestClass", bytes));
  }
}
