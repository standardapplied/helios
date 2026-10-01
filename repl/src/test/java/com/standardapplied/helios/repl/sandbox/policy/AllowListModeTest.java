/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox.policy;

import static java.lang.constant.ConstantDescs.CD_Class;
import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_void;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Allow-list mode semantics. When {@code SandboxPolicy.allowedPackages} is non-empty, the verifier
 * flips for JDK-scoped owners ({@code java/}, {@code javax/}, {@code jdk/}, {@code sun/}, {@code
 * com/sun/}): default-deny, allow only if in the allow-list. Non-JDK owners (snippet's own compiled
 * classes, third-party JARs) bypass the allow-list and are always permitted. Deny rules override
 * allow rules.
 */
class AllowListModeTest {

  private static final ClassDesc CD_ProcessBuilder = ClassDesc.of("java.lang.ProcessBuilder");
  private static final ClassDesc CD_ArrayList = ClassDesc.of("java.util.ArrayList");
  private static final ClassDesc CD_HttpClient = ClassDesc.of("java.net.http.HttpClient");
  private static final ClassDesc CD_UserClass = ClassDesc.of("com.example.MyHelper");

  @Test
  void allowListPermitsListedJdkPackage() {
    var bytes =
        buildTestClass(
            code -> {
              code.new_(CD_ArrayList);
              code.dup();
              code.invokespecial(CD_ArrayList, "<init>", MethodTypeDesc.of(CD_void));
              code.pop();
            });
    var policy = SandboxPolicy.newBuilder().withAllowedPackages("java.lang", "java.util").build();
    assertDoesNotThrow(() -> new PolicyBytecodeVerifier(policy).verify("Test", bytes));
  }

  @Test
  void allowListDeniesUnlistedJdkPackage() {
    var bytes =
        buildTestClass(
            code -> {
              code.invokestatic(CD_HttpClient, "newHttpClient", MethodTypeDesc.of(CD_HttpClient));
              code.pop();
            });
    var policy = SandboxPolicy.newBuilder().withAllowedPackages("java.lang", "java.util").build();
    var ex =
        assertThrows(
            SandboxPolicyException.class,
            () -> new PolicyBytecodeVerifier(policy).verify("Test", bytes));
    assertEquals("java/net/http/HttpClient", ex.deniedOwner());
    assertEquals("allowedPackages-default-deny", ex.rule());
  }

  @Test
  void allowListBypassesNonJdkOwners() {
    var bytes =
        buildTestClass(
            code -> {
              code.invokestatic(CD_UserClass, "compute", MethodTypeDesc.of(CD_void));
            });
    var policy = SandboxPolicy.newBuilder().withAllowedPackages("java.lang").build();
    assertDoesNotThrow(() -> new PolicyBytecodeVerifier(policy).verify("Test", bytes));
  }

  @Test
  void denyRulesOverrideAllowList() {
    var bytes =
        buildTestClass(
            code -> {
              code.new_(CD_ProcessBuilder);
              code.pop();
            });
    var policy =
        SandboxPolicy.newBuilder()
            .withAllowedPackages("java.lang", "java.util")
            .withDeniedClasses("java.lang.ProcessBuilder")
            .build();
    var ex =
        assertThrows(
            SandboxPolicyException.class,
            () -> new PolicyBytecodeVerifier(policy).verify("Test", bytes));
    assertEquals("deniedClasses:java.lang.ProcessBuilder", ex.rule());
  }

  @Test
  void categoricalDenyStillFiresUnderAllowList() {
    var bytes =
        buildTestClass(
            code -> {
              code.loadConstant("x");
              code.invokestatic(CD_Class, "forName", MethodTypeDesc.of(CD_Class, CD_String));
              code.pop();
            });
    var policy =
        SandboxPolicy.newBuilder()
            .withAllowedPackages("java.lang")
            .withDenyReflection(true)
            .build();
    var ex =
        assertThrows(
            SandboxPolicyException.class,
            () -> new PolicyBytecodeVerifier(policy).verify("Test", bytes));
    assertEquals("denyReflection", ex.rule());
  }

  @Test
  void allowListInactiveWhenEmpty() {
    var bytes =
        buildTestClass(
            code -> {
              code.invokestatic(CD_HttpClient, "newHttpClient", MethodTypeDesc.of(CD_HttpClient));
              code.pop();
            });
    var policy = SandboxPolicy.newBuilder().build();
    assertDoesNotThrow(() -> new PolicyBytecodeVerifier(policy).verify("Test", bytes));
  }

  @Test
  void allowListPermitsSubpackage() {
    var bytes =
        buildTestClass(
            code -> {
              code.loadConstant(0);
              code.loadConstant(10);
              code.invokestatic(
                  ClassDesc.of("java.util.stream.IntStream"),
                  "range",
                  MethodTypeDesc.of(ClassDesc.of("java.util.stream.IntStream"), CD_int, CD_int));
              code.pop();
            });
    var policy = SandboxPolicy.newBuilder().withAllowedPackages("java.util").build();
    assertDoesNotThrow(() -> new PolicyBytecodeVerifier(policy).verify("Test", bytes));
  }

  @Test
  void allowListPartialPrefixDoesNotMatch() {
    var bytes =
        buildTestClass(
            code -> {
              code.invokestatic(
                  ClassDesc.of("java.util.ArrayList"),
                  "of",
                  MethodTypeDesc.of(ClassDesc.of("java.util.List")));
              code.pop();
            });
    var policy = SandboxPolicy.newBuilder().withAllowedPackages("java.uti").build();
    var ex =
        assertThrows(
            SandboxPolicyException.class,
            () -> new PolicyBytecodeVerifier(policy).verify("Test", bytes));
    assertEquals("allowedPackages-default-deny", ex.rule());
  }

  /** Verifies the user-code-always-allowed exception even when allow-list is otherwise empty. */
  @Test
  void allowListAllowsLdcOnUserClassConstant() {
    var bytes =
        buildTestClass(
            code -> {
              code.ldc(CD_UserClass);
              code.pop();
            });
    var policy = SandboxPolicy.newBuilder().withAllowedPackages("java.lang").build();
    assertDoesNotThrow(() -> new PolicyBytecodeVerifier(policy).verify("Test", bytes));
  }

  @Test
  void allowListDeniesLdcOnUnlistedJdkClassConstant() {
    var bytes =
        buildTestClass(
            code -> {
              code.ldc(CD_HttpClient);
              code.pop();
            });
    var policy = SandboxPolicy.newBuilder().withAllowedPackages("java.lang").build();
    assertThrows(
        SandboxPolicyException.class,
        () -> new PolicyBytecodeVerifier(policy).verify("Test", bytes));
  }

  private static byte[] buildTestClass(Consumer<java.lang.classfile.CodeBuilder> codeWriter) {
    return ClassFile.of()
        .build(
            ClassDesc.of("TestClass"),
            cb -> {
              cb.withSuperclass(CD_Object);
              cb.withMethod(
                  "test",
                  MethodTypeDesc.of(CD_void),
                  ClassFile.ACC_STATIC | ClassFile.ACC_PUBLIC,
                  mb ->
                      mb.withCode(
                          code -> {
                            codeWriter.accept(code);
                            code.return_();
                          }));
            });
  }
}
