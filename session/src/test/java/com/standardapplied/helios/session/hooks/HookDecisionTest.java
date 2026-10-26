/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

final class HookDecisionTest {

  private static final HookOutcome BLOCK = HookOutcome.block("nope");

  @Test
  void ofWrapsNameAndOutcome() {
    var d = HookDecision.of("myHook", BLOCK);
    assertFalse(d.shouldContinue());
    assertSame(BLOCK, d.outcome());
    assertEquals("myHook", d.firingHookName());
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", " \t"})
  void ofRejectsAMissingName(String name) {
    var ex = assertThrows(IllegalArgumentException.class, () -> HookDecision.of(name, BLOCK));
    assertEquals(
        "firingHookName must not be blank when outcome is non-Continue, got " + BLOCK,
        ex.getMessage());
  }

  @Test
  void ofRejectsNullOutcome() {
    var ex = assertThrows(NullPointerException.class, () -> HookDecision.of("myHook", null));
    assertEquals("outcome must not be null", ex.getMessage());
  }

  @Test
  void ofRejectsContinueOutcome() {
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> HookDecision.of("myHook", HookOutcome.cont()));
    assertEquals(
        "a Continue decision has no firing hook; construct it with a null name", ex.getMessage());
  }

  @Test
  void constructorRejectsNullOutcome() {
    var ex = assertThrows(NullPointerException.class, () -> new HookDecision("myHook", null));
    assertEquals("outcome must not be null", ex.getMessage());
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", " \t"})
  void constructorRejectsANonContinueOutcomeWithoutAName(String name) {
    var ex = assertThrows(IllegalArgumentException.class, () -> new HookDecision(name, BLOCK));
    assertEquals(
        "firingHookName must not be blank when outcome is non-Continue, got " + BLOCK,
        ex.getMessage());
  }

  @Test
  void constructorRejectsAContinueOutcomeWithAName() {
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> new HookDecision("myHook", HookOutcome.cont()));
    assertEquals(
        "firingHookName must be null when outcome is Continue, got myHook", ex.getMessage());
  }

  @Test
  void constructorAllowsContinueWithoutAName() {
    var d = new HookDecision(null, HookOutcome.cont());
    assertTrue(d.shouldContinue());
    assertInstanceOf(HookOutcome.Continue.class, d.outcome());
    assertNull(d.firingHookName());
  }

  @Test
  void aContinueDecisionTypesAsAPhaseButNeverAsAConcreteOutcome(@TempDir Path out) {
    var diagnostics =
        compile(
            out,
            """
            import com.standardapplied.helios.session.hooks.HookDecision;
            import com.standardapplied.helios.session.hooks.HookOutcome.*;
            import static com.standardapplied.helios.session.hooks.HookOutcome.cont;
            final class Probe {
              HookDecision<PreToolUse> phase = new HookDecision<>(null, cont());
              HookDecision<MutateArgs> constructed = new HookDecision<>(null, cont());
              HookDecision<MutateArgs> named = HookDecision.of("A", cont());
              HookDecision<MutateArgs> proceed = HookDecision.proceed();
            }
            """);
    assertEquals(List.of(6L, 7L, 8L), diagnostics.stream().map(Diagnostic::getLineNumber).toList());
  }

  private static String classPath() {
    return System.getProperty("java.class.path")
        + File.pathSeparator
        + System.getProperty("jdk.module.path", "");
  }

  private static List<Diagnostic<? extends JavaFileObject>> compile(Path out, String source) {
    var compiler = ToolProvider.getSystemJavaCompiler();
    var collector = new DiagnosticCollector<JavaFileObject>();
    var file =
        new SimpleJavaFileObject(URI.create("string:///Probe.java"), JavaFileObject.Kind.SOURCE) {
          @Override
          public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
          }
        };
    var options = List.of("-proc:none", "-d", out.toString(), "-cp", classPath());
    compiler.getTask(null, null, collector, options, null, List.of(file)).call();
    return collector.getDiagnostics().stream()
        .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
        .toList();
  }
}
