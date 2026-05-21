/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SandboxPolicyTest {

  @Test
  void permissiveDeniesNothing() {
    var p = SandboxPolicy.permissive();
    assertTrue(p.isPermissive());
    assertEquals(Set.of(), p.deniedClasses());
    assertEquals(Set.of(), p.deniedPackages());
    assertFalse(p.denyReflection());
    assertFalse(p.denyNativeAccess());
    assertFalse(p.denyDynamicClassDefinition());
    assertEquals(ViolationAction.THROW, p.onViolation());
  }

  @Test
  void builderDefaultsMatchPermissive() {
    assertTrue(SandboxPolicy.newBuilder().build().isPermissive());
  }

  @Test
  void builderRoundtripsAllFields() {
    var p =
        SandboxPolicy.newBuilder()
            .withDeniedClasses("java.lang.ProcessBuilder", "java.lang.Runtime")
            .withDeniedPackages("java.net.http", "java.nio.file")
            .withDenyReflection(true)
            .withDenyNativeAccess(true)
            .withDenyDynamicClassDefinition(true)
            .withOnViolation(ViolationAction.THROW)
            .build();
    assertEquals(Set.of("java.lang.ProcessBuilder", "java.lang.Runtime"), p.deniedClasses());
    assertEquals(Set.of("java.net.http", "java.nio.file"), p.deniedPackages());
    assertTrue(p.denyReflection());
    assertTrue(p.denyNativeAccess());
    assertTrue(p.denyDynamicClassDefinition());
    assertEquals(ViolationAction.THROW, p.onViolation());
    assertFalse(p.isPermissive());
  }

  @Test
  void builderSetCollectionsOverloadRoundtrips() {
    var classes = new HashSet<String>();
    classes.add("a.B");
    var packages = new HashSet<String>();
    packages.add("c.d");
    var p =
        SandboxPolicy.newBuilder().withDeniedClasses(classes).withDeniedPackages(packages).build();
    assertEquals(Set.of("a.B"), p.deniedClasses());
    assertEquals(Set.of("c.d"), p.deniedPackages());
  }

  @Test
  void isPermissiveTrueForEmptyDeniesAndNoFlags() {
    var p =
        SandboxPolicy.newBuilder()
            .withDeniedClasses()
            .withDeniedPackages()
            .withDenyReflection(false)
            .withDenyNativeAccess(false)
            .withDenyDynamicClassDefinition(false)
            .build();
    assertTrue(p.isPermissive());
  }

  @Test
  void isPermissiveFalseWhenAnyDenyClassSet() {
    var p = SandboxPolicy.newBuilder().withDeniedClasses("X").build();
    assertFalse(p.isPermissive());
  }

  @Test
  void isPermissiveFalseWhenAnyDenyPackageSet() {
    var p = SandboxPolicy.newBuilder().withDeniedPackages("x.y").build();
    assertFalse(p.isPermissive());
  }

  @Test
  void isPermissiveFalseWhenReflectionDenied() {
    var p = SandboxPolicy.newBuilder().withDenyReflection(true).build();
    assertFalse(p.isPermissive());
  }

  @Test
  void isPermissiveFalseWhenNativeDenied() {
    var p = SandboxPolicy.newBuilder().withDenyNativeAccess(true).build();
    assertFalse(p.isPermissive());
  }

  @Test
  void isPermissiveFalseWhenDynamicClassDefDenied() {
    var p = SandboxPolicy.newBuilder().withDenyDynamicClassDefinition(true).build();
    assertFalse(p.isPermissive());
  }

  @Test
  void nullDeniedClassesThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new SandboxPolicy(null, Set.of(), false, false, false, ViolationAction.THROW));
  }

  @Test
  void nullDeniedPackagesThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new SandboxPolicy(Set.of(), null, false, false, false, ViolationAction.THROW));
  }

  @Test
  void nullOnViolationThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new SandboxPolicy(Set.of(), Set.of(), false, false, false, null));
  }

  @Test
  void blankDeniedClassEntryThrows() {
    var bad = new HashSet<String>();
    bad.add("  ");
    assertThrows(
        IllegalArgumentException.class,
        () -> new SandboxPolicy(bad, Set.of(), false, false, false, ViolationAction.THROW));
  }

  @Test
  void nullDeniedClassEntryThrows() {
    var bad = new HashSet<String>();
    bad.add(null);
    assertThrows(
        IllegalArgumentException.class,
        () -> new SandboxPolicy(bad, Set.of(), false, false, false, ViolationAction.THROW));
  }

  @Test
  void blankDeniedPackageEntryThrows() {
    var bad = new HashSet<String>();
    bad.add("");
    assertThrows(
        IllegalArgumentException.class,
        () -> new SandboxPolicy(Set.of(), bad, false, false, false, ViolationAction.THROW));
  }

  @Test
  void nullDeniedPackageEntryThrows() {
    var bad = new HashSet<String>();
    bad.add(null);
    assertThrows(
        IllegalArgumentException.class,
        () -> new SandboxPolicy(Set.of(), bad, false, false, false, ViolationAction.THROW));
  }

  @Test
  void violationActionValuesEnumerated() {
    assertEquals(1, ViolationAction.values().length);
    assertEquals(ViolationAction.THROW, ViolationAction.valueOf("THROW"));
  }
}
