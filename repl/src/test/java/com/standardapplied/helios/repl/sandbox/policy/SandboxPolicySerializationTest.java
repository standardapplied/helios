/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SandboxPolicySerializationTest {

  @Test
  void roundtripPreservesAllFields() {
    var original =
        SandboxPolicy.newBuilder()
            .withDeniedClasses("java.lang.ProcessBuilder", "java.lang.Runtime")
            .withDeniedPackages("java.net.http", "java.nio.file")
            .withDenyReflection(true)
            .withDenyNativeAccess(true)
            .withDenyDynamicClassDefinition(true)
            .withDenyFileSystemAccess(true)
            .withOnViolation(ViolationAction.THROW)
            .build();
    var encoded = SandboxPolicySerialization.encode(original);
    var decoded = SandboxPolicySerialization.decode(encoded);
    assertEquals(original.deniedClasses(), decoded.deniedClasses());
    assertEquals(original.deniedPackages(), decoded.deniedPackages());
    assertEquals(original.denyReflection(), decoded.denyReflection());
    assertEquals(original.denyNativeAccess(), decoded.denyNativeAccess());
    assertEquals(original.denyDynamicClassDefinition(), decoded.denyDynamicClassDefinition());
    assertEquals(original.denyFileSystemAccess(), decoded.denyFileSystemAccess());
    assertEquals(original.onViolation(), decoded.onViolation());
  }

  @Test
  void roundtripDenyFileSystemAccessFlagInIsolation() {
    var original = SandboxPolicy.newBuilder().withDenyFileSystemAccess(true).build();
    var decoded = SandboxPolicySerialization.decode(SandboxPolicySerialization.encode(original));
    assertTrue(decoded.denyFileSystemAccess());
  }

  @Test
  void noEgressRoundtripsDenyFileSystemAccess() {
    var encoded = SandboxPolicySerialization.encode(SandboxPolicy.noEgress());
    var decoded = SandboxPolicySerialization.decode(encoded);
    assertTrue(
        decoded.denyFileSystemAccess(),
        "noEgress() carries denyFileSystemAccess and the wire must preserve it");
  }

  @Test
  void decodingPolicyWithoutDenyFileSystemAccessFieldDefaultsToFalse() {
    // Forward-compat: an older bootstrap reading a newer wire (or a hand-crafted minimal policy)
    // that omits the field must default to false rather than throw.
    var minimal = "allowedPackages=|deniedClasses=|deniedPackages=|onViolation=THROW";
    var encoded =
        java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(minimal.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var decoded = SandboxPolicySerialization.decode(encoded);
    org.junit.jupiter.api.Assertions.assertFalse(decoded.denyFileSystemAccess());
  }

  @Test
  void roundtripPermissivePolicy() {
    var encoded = SandboxPolicySerialization.encode(SandboxPolicy.permissive());
    var decoded = SandboxPolicySerialization.decode(encoded);
    assertTrue(decoded.enforcesNothing());
  }

  @Test
  void encodingIsArgvSafe() {
    var encoded =
        SandboxPolicySerialization.encode(
            SandboxPolicy.newBuilder()
                .withDeniedClasses("java.lang.ProcessBuilder")
                .withDenyReflection(true)
                .build());
    for (var c : encoded.toCharArray()) {
      assertTrue(
          (c >= 'A' && c <= 'Z')
              || (c >= 'a' && c <= 'z')
              || (c >= '0' && c <= '9')
              || c == '-'
              || c == '_',
          "Non-argv-safe character in encoding: " + c);
    }
  }

  @Test
  void encodeRejectsNull() {
    assertThrows(IllegalArgumentException.class, () -> SandboxPolicySerialization.encode(null));
  }

  @Test
  void decodeRejectsNull() {
    assertThrows(IllegalArgumentException.class, () -> SandboxPolicySerialization.decode(null));
  }

  @Test
  void decodeRejectsBlank() {
    assertThrows(IllegalArgumentException.class, () -> SandboxPolicySerialization.decode("   "));
  }

  @Test
  void decodeRejectsMalformedBase64() {
    assertThrows(
        IllegalArgumentException.class, () -> SandboxPolicySerialization.decode("!not!base64!"));
  }

  @Test
  void decodeRejectsFieldWithoutEquals() {
    var malformed =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("denyReflection".getBytes(StandardCharsets.UTF_8));
    assertThrows(
        IllegalArgumentException.class, () -> SandboxPolicySerialization.decode(malformed));
  }

  @Test
  void decodeIgnoresUnknownFields() {
    var raw = "deniedClasses=java.lang.ProcessBuilder|unknownField=garbage|denyReflection=true";
    var encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    var decoded = SandboxPolicySerialization.decode(encoded);
    assertEquals(Set.of("java.lang.ProcessBuilder"), decoded.deniedClasses());
    assertTrue(decoded.denyReflection());
  }

  @Test
  void decodeHandlesEmptyListValue() {
    var raw = "deniedClasses=|deniedPackages=";
    var encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    var decoded = SandboxPolicySerialization.decode(encoded);
    assertEquals(Set.of(), decoded.deniedClasses());
    assertEquals(Set.of(), decoded.deniedPackages());
  }

  @Test
  void decodeSkipsEmptySegmentsBetweenPipes() {
    var raw = "deniedClasses=java.lang.Runtime||denyReflection=true|";
    var encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    var decoded = SandboxPolicySerialization.decode(encoded);
    assertEquals(Set.of("java.lang.Runtime"), decoded.deniedClasses());
    assertTrue(decoded.denyReflection());
  }

  @Test
  void decodeListWithEmptyCommaSegmentsSkipsThem() {
    var raw = "deniedClasses=a.B,,c.D";
    var encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    var decoded = SandboxPolicySerialization.decode(encoded);
    assertEquals(Set.of("a.B", "c.D"), decoded.deniedClasses());
  }

  @Test
  void encodingSortsListEntriesForStability() {
    var p1 =
        SandboxPolicy.newBuilder()
            .withDeniedClasses("java.lang.Runtime", "java.lang.ProcessBuilder")
            .build();
    var p2 =
        SandboxPolicy.newBuilder()
            .withDeniedClasses("java.lang.ProcessBuilder", "java.lang.Runtime")
            .build();
    assertEquals(SandboxPolicySerialization.encode(p1), SandboxPolicySerialization.encode(p2));
  }

  @Test
  void violationActionValueParsedFromEncoded() {
    var raw = "onViolation=THROW";
    var encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    var decoded = SandboxPolicySerialization.decode(encoded);
    assertEquals(ViolationAction.THROW, decoded.onViolation());
  }

  @Test
  void permissiveEncodedFormHasNoListEntries() {
    var encoded = SandboxPolicySerialization.encode(SandboxPolicy.permissive());
    var raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    assertTrue(raw.contains("deniedClasses="));
    assertTrue(raw.contains("deniedPackages="));
    assertFalse(raw.contains("deniedClasses=java"));
  }
}
