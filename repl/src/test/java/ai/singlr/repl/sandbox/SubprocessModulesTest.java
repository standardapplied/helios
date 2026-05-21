/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SubprocessModulesTest {

  @Test
  void unrestrictedIsSingleton() {
    assertSame(SubprocessModules.unrestricted(), SubprocessModules.unrestricted());
  }

  @Test
  void unrestrictedLimitModulesArgIsEmptyUnderBothLaunchModes() {
    assertEquals("", SubprocessModules.unrestricted().limitModulesArg(true));
    assertEquals("", SubprocessModules.unrestricted().limitModulesArg(false));
  }

  @Test
  void minimalArgUnderModulepathIncludesBootstrapModule() {
    var arg = SubprocessModules.minimal().limitModulesArg(true);
    for (var required : SubprocessModules.REQUIRED_ROOTS) {
      assertTrue(arg.contains(required), () -> "missing required root: " + required);
    }
    assertTrue(
        arg.contains(SubprocessModules.BOOTSTRAP_MODULE),
        () -> "modulepath launch should include bootstrap module in arg: " + arg);
  }

  @Test
  void minimalArgUnderClasspathOmitsBootstrapModule() {
    var arg = SubprocessModules.minimal().limitModulesArg(false);
    for (var required : SubprocessModules.REQUIRED_ROOTS) {
      assertTrue(arg.contains(required), () -> "missing required root: " + required);
    }
    assertFalse(
        arg.contains(SubprocessModules.BOOTSTRAP_MODULE),
        () ->
            "classpath launch must NOT include bootstrap module (would crash subprocess); arg: "
                + arg);
  }

  @Test
  void allowingExtrasAppendsToRequiredRoots() {
    var arg = SubprocessModules.allowingExtras("java.net.http", "java.sql").limitModulesArg(true);
    for (var required : SubprocessModules.REQUIRED_ROOTS) {
      assertTrue(arg.contains(required), () -> "missing required root: " + required);
    }
    assertTrue(arg.contains("java.net.http"));
    assertTrue(arg.contains("java.sql"));
  }

  @Test
  void allowingExtrasDeduplicatesAgainstRequiredRoots() {
    var arg = SubprocessModules.allowingExtras("java.base", "java.net.http").limitModulesArg(true);
    var commaSplit = arg.split(",");
    var seen = new HashSet<String>();
    for (var part : commaSplit) {
      assertTrue(seen.add(part), () -> "duplicate module in arg: " + part);
    }
  }

  @Test
  void allowingExtrasDeduplicatesAmongInputs() {
    var modules = SubprocessModules.allowingExtras("java.net.http", "java.net.http");
    assertTrue(modules instanceof SubprocessModules.Restricted r && r.extraModules().size() == 1);
  }

  @Test
  void allowingExtrasWithNoArgsEqualsMinimal() {
    assertEquals(
        SubprocessModules.minimal().limitModulesArg(true),
        SubprocessModules.allowingExtras().limitModulesArg(true));
    assertEquals(
        SubprocessModules.minimal().limitModulesArg(false),
        SubprocessModules.allowingExtras().limitModulesArg(false));
  }

  @Test
  void allowingExtrasRejectsNullArray() {
    assertThrows(
        IllegalArgumentException.class, () -> SubprocessModules.allowingExtras((String[]) null));
  }

  @Test
  void allowingExtrasRejectsNullEntry() {
    assertThrows(
        IllegalArgumentException.class,
        () -> SubprocessModules.allowingExtras("java.net.http", null));
  }

  @Test
  void allowingExtrasRejectsBlankEntry() {
    assertThrows(
        IllegalArgumentException.class,
        () -> SubprocessModules.allowingExtras("java.net.http", "   "));
  }

  @Test
  void restrictedRecordRejectsNullSet() {
    assertThrows(IllegalArgumentException.class, () -> new SubprocessModules.Restricted(null));
  }

  @Test
  void restrictedRecordRejectsBlankEntry() {
    var bad = new HashSet<String>();
    bad.add("");
    assertThrows(IllegalArgumentException.class, () -> new SubprocessModules.Restricted(bad));
  }

  @Test
  void restrictedRecordRejectsNullEntry() {
    var bad = new HashSet<String>();
    bad.add(null);
    assertThrows(IllegalArgumentException.class, () -> new SubprocessModules.Restricted(bad));
  }

  @Test
  void restrictedRecordCopiesSetDefensively() {
    var mutable = new HashSet<String>();
    mutable.add("java.net.http");
    var restricted = new SubprocessModules.Restricted(mutable);
    mutable.add("java.sql");
    assertEquals(Set.of("java.net.http"), restricted.extraModules());
  }

  @Test
  void requiredRootsContainsJdkChainOnly() {
    assertTrue(SubprocessModules.REQUIRED_ROOTS.contains("java.base"));
    assertTrue(SubprocessModules.REQUIRED_ROOTS.contains("jdk.jshell"));
    assertTrue(SubprocessModules.REQUIRED_ROOTS.contains("jdk.compiler"));
    assertTrue(SubprocessModules.REQUIRED_ROOTS.contains("java.compiler"));
    assertFalse(
        SubprocessModules.REQUIRED_ROOTS.contains(SubprocessModules.BOOTSTRAP_MODULE),
        "BOOTSTRAP_MODULE must not be in REQUIRED_ROOTS — it's launch-mode-conditional");
  }

  @Test
  void bootstrapModuleConstantMatchesModuleInfo() {
    assertEquals("ai.singlr.repl", SubprocessModules.BOOTSTRAP_MODULE);
  }
}
