/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox.policy;

import static java.lang.constant.ConstantDescs.CD_Class;
import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_long;
import static java.lang.constant.ConstantDescs.CD_void;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PolicyBytecodeVerifier}. Each test builds a synthetic classfile via the JDK
 * Classfile API that contains one specific instruction targeting a specific owner/member, then
 * asserts the verifier rejects (or accepts) it under a given policy.
 *
 * <p>Synthetic bytecode is preferred over compiling real Java source because it lets each test
 * isolate exactly one instruction family — avoiding spurious matches against JShell wrapper
 * synthesis or javac-inserted helper calls.
 */
class PolicyBytecodeVerifierTest {

  private static final ClassDesc CD_ProcessBuilder = ClassDesc.of("java.lang.ProcessBuilder");
  private static final ClassDesc CD_Runtime = ClassDesc.of("java.lang.Runtime");
  private static final ClassDesc CD_System = ClassDesc.of("java.lang.System");
  private static final ClassDesc CD_Files = ClassDesc.of("java.nio.file.Files");
  private static final ClassDesc CD_HttpClient = ClassDesc.of("java.net.http.HttpClient");
  private static final ClassDesc CD_Method = ClassDesc.of("java.lang.reflect.Method");
  private static final ClassDesc CD_MethodHandles = ClassDesc.of("java.lang.invoke.MethodHandles");
  private static final ClassDesc CD_Lookup = ClassDesc.of("java.lang.invoke.MethodHandles$Lookup");
  private static final ClassDesc CD_PrintStream = ClassDesc.of("java.io.PrintStream");
  private static final ClassDesc CD_MemorySegment = ClassDesc.of("java.lang.foreign.MemorySegment");

  @Test
  void deniedClassesMatchesInvokeStatic() {
    var bytes =
        buildTestClass(
            code -> {
              code.loadConstant("foo");
              code.invokestatic(CD_Class, "forName", MethodTypeDesc.of(CD_Class, CD_String));
              code.pop();
            });
    var policy = SandboxPolicy.newBuilder().withDeniedClasses("java.lang.Class").build();
    var ex =
        assertThrows(
            SandboxPolicyException.class,
            () -> new PolicyBytecodeVerifier(policy).verify("Test", bytes));
    assertEquals("java/lang/Class", ex.deniedOwner());
    assertEquals("forName", ex.deniedMember());
    assertEquals("deniedClasses:java.lang.Class", ex.rule());
  }

  @Test
  void deniedClassesMatchesNew() {
    var bytes =
        buildTestClass(
            code -> {
              code.new_(CD_ProcessBuilder);
              code.pop();
            });
    var policy = SandboxPolicy.newBuilder().withDeniedClasses("java.lang.ProcessBuilder").build();
    var ex =
        assertThrows(
            SandboxPolicyException.class,
            () -> new PolicyBytecodeVerifier(policy).verify("Test", bytes));
    assertEquals("java/lang/ProcessBuilder", ex.deniedOwner());
    assertEquals("<init>", ex.deniedMember());
  }

  @Test
  void deniedClassesMatchesGetstatic() {
    var bytes =
        buildTestClass(
            code -> {
              code.getstatic(CD_System, "out", CD_PrintStream);
              code.pop();
            });
    var policy = SandboxPolicy.newBuilder().withDeniedClasses("java.lang.System").build();
    var ex =
        assertThrows(
            SandboxPolicyException.class,
            () -> new PolicyBytecodeVerifier(policy).verify("Test", bytes));
    assertEquals("java/lang/System", ex.deniedOwner());
    assertEquals("out", ex.deniedMember());
  }

  @Test
  void deniedClassesMatchesLdcClassConstant() {
    var bytes =
        buildTestClass(
            code -> {
              code.ldc(CD_ProcessBuilder);
              code.pop();
            });
    var policy = SandboxPolicy.newBuilder().withDeniedClasses("java.lang.ProcessBuilder").build();
    var ex =
        assertThrows(
            SandboxPolicyException.class,
            () -> new PolicyBytecodeVerifier(policy).verify("Test", bytes));
    assertEquals("java/lang/ProcessBuilder", ex.deniedOwner());
  }

  @Test
  void deniedClassesMatchesInvokeVirtual() {
    var bytes =
        buildTestClass(
            code -> {
              code.getstatic(CD_System, "out", CD_PrintStream);
              code.loadConstant("hi");
              code.invokevirtual(CD_PrintStream, "println", MethodTypeDesc.of(CD_void, CD_String));
            });
    var policy = SandboxPolicy.newBuilder().withDeniedClasses("java.io.PrintStream").build();
    var ex =
        assertThrows(
            SandboxPolicyException.class,
            () -> new PolicyBytecodeVerifier(policy).verify("Test", bytes));
    assertEquals("java/io/PrintStream", ex.deniedOwner());
    assertEquals("println", ex.deniedMember());
  }

  @Test
  void deniedPackagesMatchesPrefix() {
    var bytes =
        buildTestClass(
            code -> {
              code.invokestatic(CD_HttpClient, "newHttpClient", MethodTypeDesc.of(CD_HttpClient));
              code.pop();
            });
    var policy = SandboxPolicy.newBuilder().withDeniedPackages("java.net.http").build();
    var ex =
        assertThrows(
            SandboxPolicyException.class,
            () -> new PolicyBytecodeVerifier(policy).verify("Test", bytes));
    assertEquals("java/net/http/HttpClient", ex.deniedOwner());
    assertEquals("deniedPackages:java.net.http", ex.rule());
  }

  @Test
  void deniedPackagesDoesNotMatchPartialPrefix() {
    var bytes =
        buildTestClass(
            code -> {
              code.invokestatic(CD_HttpClient, "newHttpClient", MethodTypeDesc.of(CD_HttpClient));
              code.pop();
            });
    var policy = SandboxPolicy.newBuilder().withDeniedPackages("java.net.h").build();
    assertDoesNotThrow(() -> new PolicyBytecodeVerifier(policy).verify("Test", bytes));
  }

  @Test
  void denyReflectionCatchesReflectPackage() {
    var bytes =
        buildTestClass(
            code -> {
              code.aconst_null();
              code.aconst_null();
              code.aconst_null();
              code.invokevirtual(
                  CD_Method,
                  "invoke",
                  MethodTypeDesc.of(CD_Object, CD_Object, CD_Object.arrayType()));
              code.pop();
            });
    var policy = SandboxPolicy.newBuilder().withDenyReflection(true).build();
    var ex =
        assertThrows(
            SandboxPolicyException.class,
            () -> new PolicyBytecodeVerifier(policy).verify("Test", bytes));
    assertEquals("java/lang/reflect/Method", ex.deniedOwner());
    assertEquals("denyReflection", ex.rule());
  }

  @Test
  void denyReflectionCatchesInvokePackage() {
    var bytes =
        buildTestClass(
            code -> {
              code.invokestatic(CD_MethodHandles, "lookup", MethodTypeDesc.of(CD_Lookup));
              code.pop();
            });
    var policy = SandboxPolicy.newBuilder().withDenyReflection(true).build();
    var ex =
        assertThrows(
            SandboxPolicyException.class,
            () -> new PolicyBytecodeVerifier(policy).verify("Test", bytes));
    assertEquals("java/lang/invoke/MethodHandles", ex.deniedOwner());
    assertEquals("denyReflection", ex.rule());
  }

  @Test
  void denyReflectionCatchesClassForName() {
    var bytes =
        buildTestClass(
            code -> {
              code.loadConstant("x");
              code.invokestatic(CD_Class, "forName", MethodTypeDesc.of(CD_Class, CD_String));
              code.pop();
            });
    var policy = SandboxPolicy.newBuilder().withDenyReflection(true).build();
    var ex =
        assertThrows(
            SandboxPolicyException.class,
            () -> new PolicyBytecodeVerifier(policy).verify("Test", bytes));
    assertEquals("forName", ex.deniedMember());
  }

  @Test
  void denyReflectionCatchesClassGetDeclaredMethod() {
    var bytes =
        buildTestClass(
            code -> {
              code.aconst_null();
              code.loadConstant("x");
              code.aconst_null();
              code.invokevirtual(
                  CD_Class,
                  "getDeclaredMethod",
                  MethodTypeDesc.of(
                      ClassDesc.of("java.lang.reflect.Method"), CD_String, CD_Class.arrayType()));
              code.pop();
            });
    var policy = SandboxPolicy.newBuilder().withDenyReflection(true).build();
    var ex =
        assertThrows(
            SandboxPolicyException.class,
            () -> new PolicyBytecodeVerifier(policy).verify("Test", bytes));
    assertEquals("getDeclaredMethod", ex.deniedMember());
  }

  @Test
  void denyReflectionCatchesClassGetMethod() {
    var bytes =
        buildTestClass(
            code -> {
              code.ldc(CD_String);
              code.loadConstant("toUpperCase");
              code.aconst_null();
              code.invokevirtual(
                  CD_Class,
                  "getMethod",
                  MethodTypeDesc.of(CD_Method, CD_String, CD_Class.arrayType()));
              code.pop();
            });
    var policy = SandboxPolicy.newBuilder().withDenyReflection(true).build();
    var ex =
        assertThrows(
            SandboxPolicyException.class,
            () -> new PolicyBytecodeVerifier(policy).verify("Test", bytes));
    assertEquals("getMethod", ex.deniedMember());
  }

  @Test
  void denyReflectionCatchesClassGetField() {
    var bytes =
        buildTestClass(
            code -> {
              code.ldc(CD_String);
              code.loadConstant("CASE_INSENSITIVE_ORDER");
              code.invokevirtual(
                  CD_Class,
                  "getField",
                  MethodTypeDesc.of(ClassDesc.of("java.lang.reflect.Field"), CD_String));
              code.pop();
            });
    var policy = SandboxPolicy.newBuilder().withDenyReflection(true).build();
    var ex =
        assertThrows(
            SandboxPolicyException.class,
            () -> new PolicyBytecodeVerifier(policy).verify("Test", bytes));
    assertEquals("getField", ex.deniedMember());
  }

  @Test
  void denyReflectionCatchesClassGetConstructor() {
    var bytes =
        buildTestClass(
            code -> {
              code.ldc(CD_String);
              code.aconst_null();
              code.invokevirtual(
                  CD_Class,
                  "getConstructor",
                  MethodTypeDesc.of(
                      ClassDesc.of("java.lang.reflect.Constructor"), CD_Class.arrayType()));
              code.pop();
            });
    var policy = SandboxPolicy.newBuilder().withDenyReflection(true).build();
    var ex =
        assertThrows(
            SandboxPolicyException.class,
            () -> new PolicyBytecodeVerifier(policy).verify("Test", bytes));
    assertEquals("getConstructor", ex.deniedMember());
  }

  @Test
  void denyDynamicClassDefinitionAllowsUnrelatedLookupMethod() {
    var bytes =
        buildTestClass(
            code -> {
              code.aconst_null();
              code.invokevirtual(CD_Lookup, "lookupClass", MethodTypeDesc.of(CD_Class));
              code.pop();
            });
    var policy = SandboxPolicy.newBuilder().withDenyDynamicClassDefinition(true).build();
    assertDoesNotThrow(() -> new PolicyBytecodeVerifier(policy).verify("Test", bytes));
  }

  @Test
  void denyReflectionCatchesClassNewInstance() {
    var bytes =
        buildTestClass(
            code -> {
              code.ldc(CD_String);
              code.invokevirtual(CD_Class, "newInstance", MethodTypeDesc.of(CD_Object));
              code.pop();
            });
    var policy = SandboxPolicy.newBuilder().withDenyReflection(true).build();
    var ex =
        assertThrows(
            SandboxPolicyException.class,
            () -> new PolicyBytecodeVerifier(policy).verify("Test", bytes));
    assertEquals("newInstance", ex.deniedMember());
  }

  @Test
  void denyReflectionCatchesClassGetRecordComponents() {
    var bytes =
        buildTestClass(
            code -> {
              code.ldc(CD_String);
              code.invokevirtual(
                  CD_Class,
                  "getRecordComponents",
                  MethodTypeDesc.of(ClassDesc.of("java.lang.reflect.RecordComponent").arrayType()));
              code.pop();
            });
    var policy = SandboxPolicy.newBuilder().withDenyReflection(true).build();
    var ex =
        assertThrows(
            SandboxPolicyException.class,
            () -> new PolicyBytecodeVerifier(policy).verify("Test", bytes));
    assertEquals("getRecordComponents", ex.deniedMember());
  }

  @Test
  void denyReflectionCatchesClassGetEnclosingMethod() {
    var bytes =
        buildTestClass(
            code -> {
              code.ldc(CD_String);
              code.invokevirtual(CD_Class, "getEnclosingMethod", MethodTypeDesc.of(CD_Method));
              code.pop();
            });
    var policy = SandboxPolicy.newBuilder().withDenyReflection(true).build();
    var ex =
        assertThrows(
            SandboxPolicyException.class,
            () -> new PolicyBytecodeVerifier(policy).verify("Test", bytes));
    assertEquals("getEnclosingMethod", ex.deniedMember());
  }

  @Test
  void denyReflectionCatchesClassGetEnclosingConstructor() {
    var bytes =
        buildTestClass(
            code -> {
              code.ldc(CD_String);
              code.invokevirtual(
                  CD_Class,
                  "getEnclosingConstructor",
                  MethodTypeDesc.of(ClassDesc.of("java.lang.reflect.Constructor")));
              code.pop();
            });
    var policy = SandboxPolicy.newBuilder().withDenyReflection(true).build();
    var ex =
        assertThrows(
            SandboxPolicyException.class,
            () -> new PolicyBytecodeVerifier(policy).verify("Test", bytes));
    assertEquals("getEnclosingConstructor", ex.deniedMember());
  }

  @Test
  void denyReflectionAllowsClassGetName() {
    var bytes =
        buildTestClass(
            code -> {
              code.ldc(CD_String);
              code.invokevirtual(CD_Class, "getName", MethodTypeDesc.of(CD_String));
              code.pop();
            });
    var policy = SandboxPolicy.newBuilder().withDenyReflection(true).build();
    assertDoesNotThrow(() -> new PolicyBytecodeVerifier(policy).verify("Test", bytes));
  }

  @Test
  void denyNativeAccessCatchesForeignPackage() {
    var bytes =
        buildTestClass(
            code -> {
              code.aconst_null();
              code.invokestatic(
                  CD_MemorySegment, "ofArray", MethodTypeDesc.of(CD_MemorySegment, CD_Object));
              code.pop();
            });
    var policy = SandboxPolicy.newBuilder().withDenyNativeAccess(true).build();
    var ex =
        assertThrows(
            SandboxPolicyException.class,
            () -> new PolicyBytecodeVerifier(policy).verify("Test", bytes));
    assertEquals("java/lang/foreign/MemorySegment", ex.deniedOwner());
    assertEquals("denyNativeAccess", ex.rule());
  }

  @Test
  void denyNativeAccessCatchesSystemLoadLibrary() {
    var bytes =
        buildTestClass(
            code -> {
              code.loadConstant("foo");
              code.invokestatic(CD_System, "loadLibrary", MethodTypeDesc.of(CD_void, CD_String));
            });
    var policy = SandboxPolicy.newBuilder().withDenyNativeAccess(true).build();
    var ex =
        assertThrows(
            SandboxPolicyException.class,
            () -> new PolicyBytecodeVerifier(policy).verify("Test", bytes));
    assertEquals("loadLibrary", ex.deniedMember());
    assertEquals("denyNativeAccess", ex.rule());
  }

  @Test
  void denyNativeAccessAllowsOtherSystemMethods() {
    var bytes =
        buildTestClass(
            code -> {
              code.invokestatic(CD_System, "currentTimeMillis", MethodTypeDesc.of(CD_long));
              code.pop2();
            });
    var policy = SandboxPolicy.newBuilder().withDenyNativeAccess(true).build();
    assertDoesNotThrow(() -> new PolicyBytecodeVerifier(policy).verify("Test", bytes));
  }

  @Test
  void denyDynamicClassDefinitionCatchesLookupDefineClass() {
    var bytes =
        buildTestClass(
            code -> {
              code.aconst_null();
              code.aconst_null();
              code.invokevirtual(
                  CD_Lookup,
                  "defineClass",
                  MethodTypeDesc.of(CD_Class, ClassDesc.of("byte").arrayType()));
              code.pop();
            });
    var policy = SandboxPolicy.newBuilder().withDenyDynamicClassDefinition(true).build();
    var ex =
        assertThrows(
            SandboxPolicyException.class,
            () -> new PolicyBytecodeVerifier(policy).verify("Test", bytes));
    assertEquals("defineClass", ex.deniedMember());
    assertEquals("denyDynamicClassDefinition", ex.rule());
  }

  @Test
  void rulePriorityExplicitClassBeatsCategoricalReflection() {
    var bytes =
        buildTestClass(
            code -> {
              code.loadConstant("x");
              code.invokestatic(CD_Class, "forName", MethodTypeDesc.of(CD_Class, CD_String));
              code.pop();
            });
    var policy =
        SandboxPolicy.newBuilder()
            .withDeniedClasses("java.lang.Class")
            .withDenyReflection(true)
            .build();
    var ex =
        assertThrows(
            SandboxPolicyException.class,
            () -> new PolicyBytecodeVerifier(policy).verify("Test", bytes));
    assertEquals("deniedClasses:java.lang.Class", ex.rule());
  }

  @Test
  void rulePriorityDeniedPackageBeatsCategoricalReflection() {
    var bytes =
        buildTestClass(
            code -> {
              code.aconst_null();
              code.aconst_null();
              code.aconst_null();
              code.invokevirtual(
                  CD_Method,
                  "invoke",
                  MethodTypeDesc.of(CD_Object, CD_Object, CD_Object.arrayType()));
              code.pop();
            });
    var policy =
        SandboxPolicy.newBuilder()
            .withDeniedPackages("java.lang.reflect")
            .withDenyReflection(true)
            .build();
    var ex =
        assertThrows(
            SandboxPolicyException.class,
            () -> new PolicyBytecodeVerifier(policy).verify("Test", bytes));
    assertEquals("deniedPackages:java.lang.reflect", ex.rule());
  }

  @Test
  void permissivePolicyFromForPolicyReturnsNoOp() {
    var verifier = BytecodeVerifier.forPolicy(SandboxPolicy.permissive());
    assertEquals(BytecodeVerifier.NO_OP, verifier);
  }

  @Test
  void nonPermissivePolicyFromForPolicyReturnsRealVerifier() {
    var policy = SandboxPolicy.newBuilder().withDenyReflection(true).build();
    var verifier = BytecodeVerifier.forPolicy(policy);
    assertInstanceOf(PolicyBytecodeVerifier.class, verifier);
  }

  @Test
  void nullPolicyConstructorThrows() {
    assertThrows(IllegalArgumentException.class, () -> new PolicyBytecodeVerifier(null));
  }

  @Test
  void methodWithoutCodeIsSkipped() {
    var bytes =
        ClassFile.of()
            .build(
                ClassDesc.of("AbstractTest"),
                cb -> {
                  cb.withSuperclass(CD_Object);
                  cb.withFlags(ClassFile.ACC_ABSTRACT | ClassFile.ACC_PUBLIC);
                  cb.withMethod(
                      "abstractMethod",
                      MethodTypeDesc.of(CD_void),
                      ClassFile.ACC_ABSTRACT | ClassFile.ACC_PUBLIC,
                      mb -> {});
                });
    var policy = SandboxPolicy.newBuilder().withDenyReflection(true).build();
    assertDoesNotThrow(() -> new PolicyBytecodeVerifier(policy).verify("AbstractTest", bytes));
  }

  @Test
  void multipleMethodsAllScanned() {
    var bytes =
        ClassFile.of()
            .build(
                ClassDesc.of("MultiMethod"),
                cb -> {
                  cb.withSuperclass(CD_Object);
                  cb.withMethod(
                      "safe",
                      MethodTypeDesc.of(CD_void),
                      ClassFile.ACC_STATIC | ClassFile.ACC_PUBLIC,
                      mb -> mb.withCode(code -> code.return_()));
                  cb.withMethod(
                      "unsafe",
                      MethodTypeDesc.of(CD_void),
                      ClassFile.ACC_STATIC | ClassFile.ACC_PUBLIC,
                      mb ->
                          mb.withCode(
                              code -> {
                                code.loadConstant("x");
                                code.invokestatic(
                                    CD_Class, "forName", MethodTypeDesc.of(CD_Class, CD_String));
                                code.pop();
                                code.return_();
                              }));
                });
    var policy = SandboxPolicy.newBuilder().withDenyReflection(true).build();
    assertThrows(
        SandboxPolicyException.class,
        () -> new PolicyBytecodeVerifier(policy).verify("MultiMethod", bytes));
  }

  @Test
  void packageDenyAlsoCatchesDeeperSubpackages() {
    var bytes =
        buildTestClass(
            code -> {
              code.loadConstant("rwx------");
              code.invokestatic(
                  ClassDesc.of("java.nio.file.attribute.PosixFilePermissions"),
                  "fromString",
                  MethodTypeDesc.of(ClassDesc.of("java.util.Set"), CD_String));
              code.pop();
            });
    var policy = SandboxPolicy.newBuilder().withDeniedPackages("java.nio.file").build();
    var ex =
        assertThrows(
            SandboxPolicyException.class,
            () -> new PolicyBytecodeVerifier(policy).verify("Test", bytes));
    assertEquals("java/nio/file/attribute/PosixFilePermissions", ex.deniedOwner());
  }

  @Test
  void filesClassDeniedByDirectClassName() {
    var bytes =
        buildTestClass(
            code -> {
              code.aconst_null();
              code.invokestatic(
                  CD_Files,
                  "walk",
                  MethodTypeDesc.of(
                      ClassDesc.of("java.util.stream.Stream"), ClassDesc.of("java.nio.file.Path")));
              code.pop();
            });
    var policy = SandboxPolicy.newBuilder().withDeniedClasses("java.nio.file.Files").build();
    var ex =
        assertThrows(
            SandboxPolicyException.class,
            () -> new PolicyBytecodeVerifier(policy).verify("Test", bytes));
    assertEquals("java/nio/file/Files", ex.deniedOwner());
    assertEquals("walk", ex.deniedMember());
  }

  @Test
  void runtimeClassDeniedByDirectClassName() {
    var bytes =
        buildTestClass(
            code -> {
              code.invokestatic(CD_Runtime, "getRuntime", MethodTypeDesc.of(CD_Runtime));
              code.pop();
            });
    var policy = SandboxPolicy.newBuilder().withDeniedClasses("java.lang.Runtime").build();
    var ex =
        assertThrows(
            SandboxPolicyException.class,
            () -> new PolicyBytecodeVerifier(policy).verify("Test", bytes));
    assertEquals("java/lang/Runtime", ex.deniedOwner());
  }

  /**
   * Build a single-method test class with the body written by {@code codeWriter}. The method is a
   * public static void {@code test()} and the writer must leave the operand stack balanced (the
   * helper appends {@code return} after).
   */
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
