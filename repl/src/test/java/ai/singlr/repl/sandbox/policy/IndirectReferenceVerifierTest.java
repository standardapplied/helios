/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox.policy;

import static ai.singlr.repl.sandbox.policy.PolicyBytecodeVerifierTest.buildTestClass;
import static java.lang.constant.ConstantDescs.BSM_INVOKE;
import static java.lang.constant.ConstantDescs.CD_CallSite;
import static java.lang.constant.ConstantDescs.CD_Class;
import static java.lang.constant.ConstantDescs.CD_MethodHandle;
import static java.lang.constant.ConstantDescs.CD_MethodType;
import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_int;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.ConstantDynamicEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.DynamicConstantDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the indirect-reference closure in {@link PolicyBytecodeVerifier}: references that
 * reach a denied API only through an {@code INVOKEDYNAMIC} bootstrap argument, a method-handle
 * constant or a dynamic constant. Each test builds a synthetic classfile via the Classfile API so
 * exactly one indirect reference is under test.
 */
class IndirectReferenceVerifierTest {

  private static final ClassDesc CD_TestClass = ClassDesc.of("TestClass");
  private static final ClassDesc CD_FileReader = ClassDesc.of("java.io.FileReader");
  private static final ClassDesc CD_File = ClassDesc.of("java.io.File");
  private static final ClassDesc CD_ProcessBuilder = ClassDesc.of("java.lang.ProcessBuilder");
  private static final ClassDesc CD_Function = ClassDesc.of("java.util.function.Function");
  private static final ClassDesc CD_LambdaMetafactory =
      ClassDesc.of("java.lang.invoke.LambdaMetafactory");
  private static final ClassDesc CD_MethodHandles = ClassDesc.of("java.lang.invoke.MethodHandles");

  private static final DirectMethodHandleDesc LAMBDA_METAFACTORY =
      ConstantDescs.ofCallsiteBootstrap(
          CD_LambdaMetafactory,
          "metafactory",
          CD_CallSite,
          CD_MethodType,
          CD_MethodHandle,
          CD_MethodType);

  private static final MethodTypeDesc OBJECT_TO_OBJECT = MethodTypeDesc.of(CD_Object, CD_Object);

  @Test
  void constructorReferenceInBootstrapArgumentIsRejected() {
    var bytes = lambdaWithImplementation(MethodHandleDesc.ofConstructor(CD_FileReader, CD_String));
    var ex = assertDenied(SandboxPolicy.noEgress(), bytes);
    assertEquals("java/io/FileReader", ex.deniedOwner());
    assertEquals("<init>", ex.deniedMember());
    assertEquals("denyFileSystemAccess", ex.rule());
  }

  @Test
  void staticMethodReferenceInBootstrapArgumentIsRejected() {
    var bytes =
        lambdaWithImplementation(
            MethodHandleDesc.ofMethod(
                DirectMethodHandleDesc.Kind.STATIC,
                CD_Class,
                "forName",
                MethodTypeDesc.of(CD_Class, CD_String)));
    var ex = assertDenied(SandboxPolicy.newBuilder().withDenyReflection(true).build(), bytes);
    assertEquals("java/lang/Class", ex.deniedOwner());
    assertEquals("forName", ex.deniedMember());
    assertEquals("denyReflection", ex.rule());
  }

  @Test
  void instanceMethodReferenceInBootstrapArgumentIsRejected() {
    var bytes =
        lambdaWithImplementation(
            MethodHandleDesc.ofMethod(
                DirectMethodHandleDesc.Kind.VIRTUAL,
                CD_File,
                "listFiles",
                MethodTypeDesc.of(CD_File.arrayType())));
    var ex = assertDenied(SandboxPolicy.newBuilder().withDenyFileSystemAccess(true).build(), bytes);
    assertEquals("java/io/File", ex.deniedOwner());
    assertEquals("listFiles", ex.deniedMember());
  }

  @Test
  void methodHandleConstantIsRejected() {
    var bytes =
        buildTestClass(
            code -> {
              code.ldc(MethodHandleDesc.ofConstructor(CD_ProcessBuilder, CD_String.arrayType()));
              code.pop();
            });
    var ex =
        assertDenied(
            SandboxPolicy.newBuilder().withDeniedClasses("java.lang.ProcessBuilder").build(),
            bytes);
    assertEquals("java/lang/ProcessBuilder", ex.deniedOwner());
    assertEquals("<init>", ex.deniedMember());
  }

  @Test
  void dynamicConstantInvokingDeniedHandleIsRejected() {
    var condy =
        DynamicConstantDesc.ofNamed(
            BSM_INVOKE,
            "_",
            CD_Object,
            MethodHandleDesc.ofConstructor(CD_FileReader, CD_String),
            "/etc/passwd");
    var ex = assertDenied(SandboxPolicy.noEgress(), loadConstant(condy));
    assertEquals("java/io/FileReader", ex.deniedOwner());
    assertEquals("<init>", ex.deniedMember());
  }

  @Test
  void nestedDynamicConstantReferencingDeniedHandleIsRejected() {
    var inner =
        DynamicConstantDesc.ofNamed(
            BSM_INVOKE,
            "inner",
            CD_Object,
            MethodHandleDesc.ofMethod(
                DirectMethodHandleDesc.Kind.STATIC,
                CD_MethodHandles,
                "lookup",
                MethodTypeDesc.of(ConstantDescs.CD_MethodHandles_Lookup)));
    var outer = DynamicConstantDesc.ofNamed(BSM_INVOKE, "outer", CD_Object, inner);
    var ex =
        assertDenied(
            SandboxPolicy.newBuilder().withDenyReflection(true).build(), loadConstant(outer));
    assertEquals("java/lang/invoke/MethodHandles", ex.deniedOwner());
    assertEquals("lookup", ex.deniedMember());
  }

  @Test
  void dynamicConstantWhoseTypeIsDeniedIsRejected() {
    var condy =
        DynamicConstantDesc.ofNamed(ConstantDescs.BSM_NULL_CONSTANT, "_", CD_ProcessBuilder);
    var ex =
        assertDenied(
            SandboxPolicy.newBuilder().withDeniedClasses("java.lang.ProcessBuilder").build(),
            loadConstant(condy));
    assertEquals("java/lang/ProcessBuilder", ex.deniedOwner());
    assertNull(ex.deniedMember());
  }

  @Test
  void untrustedBootstrapMethodIsCheckedLikeADirectCall() {
    var bootstrap =
        ConstantDescs.ofCallsiteBootstrap(CD_MethodHandles, "constant", CD_CallSite, CD_Object);
    var bytes =
        buildTestClass(
            code -> {
              code.invokedynamic(
                  DynamicCallSiteDesc.of(bootstrap, "get", MethodTypeDesc.of(CD_Object), "x"));
              code.pop();
            });
    var ex = assertDenied(SandboxPolicy.newBuilder().withDenyReflection(true).build(), bytes);
    assertEquals("java/lang/invoke/MethodHandles", ex.deniedOwner());
    assertEquals("constant", ex.deniedMember());
  }

  @Test
  void trustedBootstrapNameWithNonStaticKindIsNotTrusted() {
    var bootstrap =
        MethodHandleDesc.ofMethod(
            DirectMethodHandleDesc.Kind.VIRTUAL,
            CD_LambdaMetafactory,
            "metafactory",
            MethodTypeDesc.of(CD_CallSite));
    var bytes =
        buildTestClass(
            code -> {
              code.invokedynamic(
                  DynamicCallSiteDesc.of(bootstrap, "apply", MethodTypeDesc.of(CD_Function)));
              code.pop();
            });
    var ex = assertDenied(SandboxPolicy.newBuilder().withDenyReflection(true).build(), bytes);
    assertEquals("java/lang/invoke/LambdaMetafactory", ex.deniedOwner());
  }

  @Test
  void snippetOwnedBootstrapWithAllowedArgumentsIsAccepted() {
    var bootstrap = ConstantDescs.ofCallsiteBootstrap(CD_TestClass, "bsm", CD_CallSite, CD_Object);
    var bytes =
        buildTestClass(
            code -> {
              code.invokedynamic(
                  DynamicCallSiteDesc.of(bootstrap, "get", MethodTypeDesc.of(CD_Object), "x"));
              code.pop();
            });
    assertAccepted(SandboxPolicy.noEgress(), bytes);
  }

  @Test
  void snippetOwnedBootstrapWithDeniedArgumentIsRejected() {
    var bootstrap =
        ConstantDescs.ofCallsiteBootstrap(CD_TestClass, "bsm", CD_CallSite, CD_MethodHandle);
    var bytes =
        buildTestClass(
            code -> {
              code.invokedynamic(
                  DynamicCallSiteDesc.of(
                      bootstrap,
                      "get",
                      MethodTypeDesc.of(CD_Object),
                      MethodHandleDesc.ofConstructor(CD_FileReader, CD_String)));
              code.pop();
            });
    var ex = assertDenied(SandboxPolicy.noEgress(), bytes);
    assertEquals("java/io/FileReader", ex.deniedOwner());
  }

  @Test
  void allowedLambdaImplementationsAreAccepted() {
    var policy = SandboxPolicy.noEgress();
    assertAccepted(
        policy,
        lambdaWithImplementation(
            MethodHandleDesc.ofMethod(
                DirectMethodHandleDesc.Kind.STATIC,
                ClassDesc.of("java.lang.Math"),
                "abs",
                MethodTypeDesc.of(CD_int, CD_int))));
    assertAccepted(
        policy,
        lambdaWithImplementation(
            MethodHandleDesc.ofMethod(
                DirectMethodHandleDesc.Kind.STATIC,
                CD_TestClass,
                "lambda$0",
                MethodTypeDesc.of(CD_Object, CD_String, CD_Object))));
    assertAccepted(
        policy,
        lambdaWithImplementation(
            MethodHandleDesc.ofField(
                DirectMethodHandleDesc.Kind.GETTER, CD_TestClass, "captured", CD_int)));
  }

  @Test
  void arrayClassLiteralOfDeniedClassIsRejected() {
    var bytes =
        buildTestClass(
            code -> {
              code.ldc(CD_ProcessBuilder.arrayType(2));
              code.pop();
            });
    var ex =
        assertDenied(
            SandboxPolicy.newBuilder().withDeniedClasses("java.lang.ProcessBuilder").build(),
            bytes);
    assertEquals("java/lang/ProcessBuilder", ex.deniedOwner());
  }

  @Test
  void primitiveArrayReferencesAreAccepted() {
    var bytes =
        buildTestClass(
            code -> {
              code.ldc(CD_int.arrayType());
              code.pop();
              code.iconst_1();
              code.newarray(java.lang.classfile.TypeKind.INT);
              code.invokevirtual(CD_int.arrayType(), "clone", MethodTypeDesc.of(CD_Object));
              code.pop();
            });
    assertAccepted(SandboxPolicy.noEgress(), bytes);
  }

  @Test
  void dynamicConstantNestingAtTheBoundIsAccepted() {
    assertAccepted(
        SandboxPolicy.noEgress(),
        loadConstant(nestedCondy(PolicyBytecodeVerifier.MAX_CONSTANT_DEPTH)));
  }

  @Test
  void dynamicConstantNestingBeyondTheBoundIsRejected() {
    var ex =
        assertDenied(
            SandboxPolicy.noEgress(),
            loadConstant(nestedCondy(PolicyBytecodeVerifier.MAX_CONSTANT_DEPTH + 1)));
    assertEquals("dynamicConstantDepth", ex.rule());
  }

  @Test
  void cyclicDynamicConstantIsRejectedWithoutUnboundedRecursion() {
    var inner = DynamicConstantDesc.ofNamed(BSM_INVOKE, "inner", CD_Object, "seed");
    var outer = DynamicConstantDesc.ofNamed(BSM_INVOKE, "outer", CD_Object, inner);
    var bytes = makeCyclic(loadConstant(outer));
    var ex = assertDenied(SandboxPolicy.noEgress(), bytes);
    assertEquals("dynamicConstantCycle", ex.rule());
  }

  @Test
  void truncatedClassfileIsRejected() {
    var bytes = loadConstant("hello");
    var ex =
        assertDenied(SandboxPolicy.noEgress(), Arrays.copyOf(bytes, bytes.length / 2), "REPL.X");
    assertEquals("malformedClassfile", ex.rule());
    assertEquals("REPL.X", ex.deniedOwner());
    assertEquals("malformedClassfile", assertDenied(SandboxPolicy.noEgress(), new byte[0]).rule());
  }

  private static byte[] lambdaWithImplementation(DirectMethodHandleDesc implementation) {
    return buildTestClass(
        code -> {
          code.invokedynamic(
              DynamicCallSiteDesc.of(
                  LAMBDA_METAFACTORY,
                  "apply",
                  MethodTypeDesc.of(CD_Function),
                  OBJECT_TO_OBJECT,
                  implementation,
                  OBJECT_TO_OBJECT));
          code.pop();
        });
  }

  private static byte[] loadConstant(ConstantDesc constant) {
    return buildTestClass(
        code -> {
          code.ldc(constant);
          code.pop();
        });
  }

  private static DynamicConstantDesc<?> nestedCondy(int depth) {
    DynamicConstantDesc<?> condy =
        DynamicConstantDesc.ofNamed(BSM_INVOKE, "leaf", CD_Object, "seed");
    for (var i = 1; i < depth; i++) {
      condy = DynamicConstantDesc.ofNamed(BSM_INVOKE, "level" + i, CD_Object, condy);
    }
    return condy;
  }

  /**
   * Rewrites the BootstrapMethods table so the inner constant's single argument (the {@code seed}
   * string) points back at the outer constant, producing a pool cycle no builder can express.
   */
  private static byte[] makeCyclic(byte[] bytes) {
    var seedIndex = -1;
    var outerIndex = -1;
    var bootstrapIndex = -1;
    for (var entry : ClassFile.of().parse(bytes).constantPool()) {
      if (entry instanceof StringEntry s && s.stringValue().equals("seed")) {
        seedIndex = s.index();
      }
      if (entry instanceof ConstantDynamicEntry d && d.name().stringValue().equals("outer")) {
        outerIndex = d.index();
        bootstrapIndex = d.bootstrap().bootstrapMethod().index();
      }
    }
    var pattern =
        new byte[] {
          (byte) (bootstrapIndex >> 8),
          (byte) bootstrapIndex,
          0,
          1,
          (byte) (seedIndex >> 8),
          (byte) seedIndex
        };
    var patched = bytes.clone();
    var matches = 0;
    for (var i = 0; i <= patched.length - pattern.length; i++) {
      if (Arrays.equals(patched, i, i + pattern.length, pattern, 0, pattern.length)) {
        patched[i + 4] = (byte) (outerIndex >> 8);
        patched[i + 5] = (byte) outerIndex;
        matches++;
      }
    }
    assertEquals(1, matches, "expected exactly one inner bootstrap argument slot to patch");
    return patched;
  }

  private static SandboxPolicyException assertDenied(SandboxPolicy policy, byte[] bytes) {
    return assertDenied(policy, bytes, "TestClass");
  }

  private static SandboxPolicyException assertDenied(
      SandboxPolicy policy, byte[] bytes, String internalName) {
    return assertThrows(
        SandboxPolicyException.class,
        () -> new PolicyBytecodeVerifier(policy).verify(internalName, bytes));
  }

  private static void assertAccepted(SandboxPolicy policy, byte[] bytes) {
    assertDoesNotThrow(() -> new PolicyBytecodeVerifier(policy).verify("TestClass", bytes));
  }
}
