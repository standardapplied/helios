/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.sandbox.policy;

import static java.lang.constant.ConstantDescs.CD_CallSite;
import static java.lang.constant.ConstantDescs.CD_MethodHandle;
import static java.lang.constant.ConstantDescs.CD_MethodType;
import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_void;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The verifier's decision for one owner/member reference under the permissive, {@code noEgress} and
 * an allow-list policy: the rule label it rejects with, or {@code allowed}. Rows cover every rule
 * and the edges of their order: a class denied explicitly inside an allowed package and inside a
 * denied package, a denied package that is also reflective, the reflective entry points on {@code
 * java.lang.Class} next to its plain members, user code outside the allow-list, and a trusted
 * language bootstrap whose argument is denied.
 *
 * <p>A reference is written {@code kind owner member}: {@code call} invokes a static method, {@code
 * new} instantiates, {@code class} loads a class literal, {@code lambda} is a {@code
 * LambdaMetafactory} call site whose implementation is {@code owner.member} ({@code <init>} for a
 * constructor).
 */
class PolicyDecisionTableTest {

  private static final DirectMethodHandleDesc LAMBDA_METAFACTORY =
      ConstantDescs.ofCallsiteBootstrap(
          ClassDesc.of("java.lang.invoke.LambdaMetafactory"),
          "metafactory",
          CD_CallSite,
          CD_MethodType,
          CD_MethodHandle,
          CD_MethodType);

  private static final MethodTypeDesc OBJECT_TO_OBJECT = MethodTypeDesc.of(CD_Object, CD_Object);

  @ParameterizedTest(name = "{0}: {1} {2}.{3} -> {4}")
  @CsvSource({
    "permissive, call,   java.lang.ProcessBuilder,              start,               allowed",
    "permissive, call,   java.lang.Class,                       forName,             allowed",
    "permissive, call,   java.nio.file.Files,                   readAllBytes,        allowed",
    "permissive, lambda, java.io.FileReader,                    <init>,              allowed",
    "noEgress,   new,    java.lang.ProcessBuilder,              <init>,              deniedClasses:java.lang.ProcessBuilder",
    "noEgress,   call,   java.lang.Runtime,                     getRuntime,          deniedClasses:java.lang.Runtime",
    "noEgress,   class,  java.lang.Thread,                      ,                    deniedClasses:java.lang.Thread",
    "noEgress,   call,   java.lang.Class,                       forName,             denyReflection",
    "noEgress,   call,   java.lang.Class,                       getDeclaredMethods,  denyReflection",
    "noEgress,   call,   java.lang.Class,                       getRecordComponents, denyReflection",
    "noEgress,   call,   java.lang.Class,                       getName,             allowed",
    "noEgress,   class,  java.lang.Class,                       ,                    allowed",
    "noEgress,   call,   java.lang.reflect.Method,              invoke,              denyReflection",
    "noEgress,   call,   java.lang.invoke.MethodHandles$Lookup, defineClass,         denyReflection",
    "noEgress,   call,   java.lang.System,                      loadLibrary,         denyNativeAccess",
    "noEgress,   call,   java.lang.System,                      currentTimeMillis,   allowed",
    "noEgress,   call,   java.lang.foreign.Linker,              nativeLinker,        denyNativeAccess",
    "noEgress,   call,   java.lang.ClassLoader,                 defineClass,         denyDynamicClassDefinition",
    "noEgress,   call,   java.nio.file.Files,                   readAllBytes,        denyFileSystemAccess",
    "noEgress,   new,    java.io.FileInputStream,               <init>,              denyFileSystemAccess",
    "noEgress,   call,   java.io.File,                          exists,              denyFileSystemAccess",
    "noEgress,   call,   java.io.File,                          getName,             allowed",
    "noEgress,   call,   java.net.URI,                          create,              allowedPackages-default-deny",
    "noEgress,   class,  javax.crypto.Cipher,                   ,                    allowedPackages-default-deny",
    "noEgress,   call,   java.util.concurrent.Executors,        newFixedThreadPool,  allowed",
    "noEgress,   call,   java.util.concurrent.atomic.AtomicLong, get,                allowed",
    "noEgress,   call,   com.example.Helper,                    run,                 allowed",
    "noEgress,   lambda, java.io.FileReader,                    <init>,              denyFileSystemAccess",
    "noEgress,   lambda, java.lang.ProcessBuilder,              <init>,              deniedClasses:java.lang.ProcessBuilder",
    "noEgress,   lambda, java.lang.String,                      valueOf,             allowed",
    "allowList,  call,   java.util.Random,                      nextInt,             deniedClasses:java.util.Random",
    "allowList,  call,   java.util.concurrent.Executors,        newFixedThreadPool,  deniedPackages:java.util.concurrent",
    "allowList,  call,   java.lang.reflect.Array,               newInstance,         deniedPackages:java.lang.reflect",
    "allowList,  call,   java.lang.reflect.Proxy,               newProxyInstance,    deniedClasses:java.lang.reflect.Proxy",
    "allowList,  call,   java.lang.invoke.MethodHandles$Lookup, defineHiddenClass,   denyDynamicClassDefinition",
    "allowList,  call,   java.lang.Class,                       forName,             allowed",
    "allowList,  call,   java.util.List,                        of,                  allowed",
    "allowList,  call,   java.text.NumberFormat,                getInstance,         allowedPackages-default-deny",
    "allowList,  call,   sun.misc.Unsafe,                       getUnsafe,           allowedPackages-default-deny",
    "allowList,  call,   com.sun.net.httpserver.HttpServer,     create,              allowedPackages-default-deny",
    "allowList,  call,   jdk.internal.misc.Unsafe,              getUnsafe,           allowedPackages-default-deny",
    "allowList,  call,   javaxx.Custom,                         run,                 allowed",
  })
  void decision(String policy, String kind, String owner, String member, String expected) {
    var bytes = referencing(kind, ClassDesc.of(owner), member);

    assertEquals(expected, decide(policy(policy), bytes));
  }

  private static String decide(SandboxPolicy policy, byte[] bytes) {
    try {
      new PolicyBytecodeVerifier(policy).verify("TestClass", bytes);
      return "allowed";
    } catch (SandboxPolicyException e) {
      return e.rule();
    }
  }

  private static byte[] referencing(String kind, ClassDesc owner, String member) {
    return PolicyBytecodeVerifierTest.buildTestClass(
        code -> {
          switch (kind) {
            case "call" -> code.invokestatic(owner, member, MethodTypeDesc.of(CD_void));
            case "new" -> {
              code.new_(owner);
              code.pop();
            }
            case "class" -> {
              code.ldc(owner);
              code.pop();
            }
            case "lambda" -> {
              code.invokedynamic(
                  DynamicCallSiteDesc.of(
                      LAMBDA_METAFACTORY,
                      "apply",
                      MethodTypeDesc.of(ClassDesc.of("java.util.function.Function")),
                      OBJECT_TO_OBJECT,
                      implementation(owner, member),
                      OBJECT_TO_OBJECT));
              code.pop();
            }
            default -> throw new IllegalArgumentException(kind);
          }
        });
  }

  private static DirectMethodHandleDesc implementation(ClassDesc owner, String member) {
    if (member.equals("<init>")) {
      return MethodHandleDesc.ofConstructor(owner, CD_String);
    }
    return MethodHandleDesc.ofMethod(
        DirectMethodHandleDesc.Kind.STATIC, owner, member, MethodTypeDesc.of(CD_String, CD_Object));
  }

  private static SandboxPolicy policy(String name) {
    return switch (name) {
      case "permissive" -> SandboxPolicy.permissive();
      case "noEgress" -> SandboxPolicy.noEgress();
      case "allowList" ->
          SandboxPolicy.newBuilder()
              .withAllowedPackages("java.lang", "java.util")
              .withDeniedClasses("java.util.Random", "java.lang.reflect.Proxy")
              .withDeniedPackages("java.util.concurrent", "java.lang.reflect")
              .withDenyDynamicClassDefinition(true)
              .build();
      default -> throw new IllegalArgumentException(name);
    };
  }
}
